/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.util.fxdesigner;

import static java.util.Collections.emptyList;
import static net.sourceforge.pmd.util.fxdesigner.util.DesignerUtil.sanitizeExceptionMessage;
import static net.sourceforge.pmd.util.fxdesigner.util.LanguageRegistryUtil.defaultLanguageVersion;
import static net.sourceforge.pmd.util.fxdesigner.util.reactfx.ReactfxUtil.latestValue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.controlsfx.control.PopOver;
import org.reactfx.EventSource;
import org.reactfx.Subscription;
import org.reactfx.SuspendableEventStream;
import org.reactfx.collection.LiveArrayList;
import org.reactfx.value.SuspendableVar;
import org.reactfx.value.Val;
import org.reactfx.value.Var;

import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.util.ClasspathClassLoader;
import net.sourceforge.pmd.util.fxdesigner.app.AbstractController;
import net.sourceforge.pmd.util.fxdesigner.app.DesignerRoot;
import net.sourceforge.pmd.util.fxdesigner.app.services.ASTManager;
import net.sourceforge.pmd.util.fxdesigner.app.services.ASTManagerImpl;
import net.sourceforge.pmd.util.fxdesigner.app.services.TestCreatorService;
import net.sourceforge.pmd.util.fxdesigner.model.testing.LiveTestCase;
import net.sourceforge.pmd.util.fxdesigner.model.testing.LiveViolationRecord;
import net.sourceforge.pmd.util.fxdesigner.popups.AuxclasspathSetupController;
import net.sourceforge.pmd.util.fxdesigner.popups.SimplePopups;
import net.sourceforge.pmd.util.fxdesigner.util.DesignerUtil;
import net.sourceforge.pmd.util.fxdesigner.util.LanguageRegistryUtil;
import net.sourceforge.pmd.util.fxdesigner.util.ResourceUtil;
import net.sourceforge.pmd.util.fxdesigner.util.beans.SettingsOwner;
import net.sourceforge.pmd.util.fxdesigner.util.beans.SettingsPersistenceUtil.PersistentProperty;
import net.sourceforge.pmd.util.fxdesigner.util.controls.AstTreeView;
import net.sourceforge.pmd.util.fxdesigner.util.controls.DragAndDropUtil;
import net.sourceforge.pmd.util.fxdesigner.util.controls.DynamicWidthChoicebox;
import net.sourceforge.pmd.util.fxdesigner.util.controls.NodeEditionCodeArea;
import net.sourceforge.pmd.util.fxdesigner.util.controls.NodeParentageCrumbBar;
import net.sourceforge.pmd.util.fxdesigner.util.controls.PopOverWrapper;
import net.sourceforge.pmd.util.fxdesigner.util.controls.ToolbarTitledPane;
import net.sourceforge.pmd.util.fxdesigner.util.controls.ViolationCollectionView;
import net.sourceforge.pmd.util.fxdesigner.util.reactfx.ReactfxUtil;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.AnchorPane;


/**
 * One editor, i.e. source editor and ast tree view. The {@link NodeEditionCodeArea} handles the
 * presentation of different types of nodes in separate layers. This class handles configuration,
 * language selection and such.
 *
 * @author Clément Fournier
 * @since 6.0.0
 */
public class SourceEditorController extends AbstractController {

    /**
     * When no user-defined test case is loaded, then this is where
     * source changes end up.
     */
    private final LiveTestCase defaultTestCase = new LiveTestCase();
    /** Contains the loaded *user-defined* test case. */
    private final SuspendableVar<LiveTestCase> currentlyOpenTestCase = Var.suspendable(Var.newSimpleVar(null));
    private static final Duration AST_REFRESH_DELAY = Duration.ofMillis(100);
    private final ASTManager astManager;
    private final Var<List<File>> auxclasspathFiles = Var.newSimpleVar(emptyList());
    private final Val<ClassLoader> auxclasspathClassLoader = auxclasspathFiles.<ClassLoader>map(fileList -> {
        try {
            return new ClasspathClassLoader(fileList, SourceEditorController.class.getClassLoader());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }).orElseConst(SourceEditorController.class.getClassLoader());

    @FXML
    private Button convertToTestCaseButton;
    @FXML
    private DynamicWidthChoicebox<LanguageVersion> languageVersionChoicebox;
    @FXML
    private ToolbarTitledPane testCaseToolsTitledPane;
    @FXML
    private Button violationsButton;
    @FXML
    private Button propertiesMapButton;


    @FXML
    private ToolbarTitledPane astTitledPane;
    @FXML
    private ToolbarTitledPane editorTitledPane;
    @FXML
    private MenuButton languageSelectionMenuButton;
    @FXML
    private AstTreeView astTreeView;
    @FXML
    private NodeEditionCodeArea nodeEditionCodeArea;
    @FXML
    private NodeParentageCrumbBar focusNodeParentageCrumbBar;

    private final PopOverWrapper<LiveTestCase> violationsPopover;

    private Var<LanguageVersion> languageVersionUIProperty;


    public SourceEditorController(DesignerRoot designerRoot) {
        super(designerRoot);
        this.astManager = new ASTManagerImpl(designerRoot);

        designerRoot.registerService(DesignerRoot.AST_MANAGER, astManager);

        violationsPopover = new PopOverWrapper<>(this::rebindPopover);
    }

    private PopOver rebindPopover(LiveTestCase testCase, PopOver existing) {
        if (testCase == null && existing != null) {
            existing.hide();
            return existing;
        }

        if (testCase != null) {
            if (existing == null) {
                return ViolationCollectionView.makePopOver(testCase, getDesignerRoot());
            } else {
                ViolationCollectionView view = (ViolationCollectionView) existing.getUserData();
                view.setItems(testCase.getExpectedViolations());
                return existing;
            }
        }
        return null;
    }

    private EventSource<Boolean> convertButtonVisibilitySource = new EventSource<>();
    private SuspendableEventStream<Boolean> convertButtonVisibility = convertButtonVisibilitySource.suppressible();

    @Override
    protected void beforeParentInit() {

        astManager.languageVersionProperty()
                  .map(LanguageVersion::getLanguage)
                  .values()
                  .filter(Objects::nonNull)
                  .subscribe(nodeEditionCodeArea::updateSyntaxHighlighter);

        ((ASTManagerImpl) astManager).classLoaderProperty().bind(auxclasspathClassLoader);

        // default text, will be overwritten by settings restore
        setText(getDefaultText());

        TestCreatorService creatorService = getService(DesignerRoot.TEST_CREATOR);
        convertToTestCaseButton.setOnAction(
            e -> {
                convertButtonVisibility.suspendWhile(() -> creatorService.getAdditionRequests().pushEvent(this, defaultTestCase.deepCopy()));
                SimplePopups.showActionFeedback(convertToTestCaseButton, AlertType.CONFIRMATION, "Test created")
                            .subscribeForOne(done -> convertButtonVisibilitySource.push(false));

            }
        );

        creatorService.getSourceFetchRequests()
                      .messageStream(true, this)
                      .subscribe(tick -> convertToTestCaseButton.fire());


        violationsButton.setOnAction(e -> violationsPopover.showOrFocus(p -> p.show(violationsButton)));

        violationsButton.textProperty().bind(
            currentlyOpenTestCase.flatMap(it -> it.getExpectedViolations().sizeProperty())
                                 .map(it -> "Expected violations (" + it + ")")
                                 .orElseConst("Expected violations")
        );

        DragAndDropUtil.registerAsNodeDragTarget(
            violationsButton,
            range -> {
                LiveViolationRecord record = new LiveViolationRecord();
                record.setRange(range);
                record.setExactRange(true);
                currentlyOpenTestCase.ifPresent(v -> v.getExpectedViolations().add(record));
            });

        currentlyOpenTestCase.orElseConst(defaultTestCase)
                             .changes()
                             .subscribe(it -> handleTestOpenRequest(it.getOldValue(), it.getNewValue()));

        currentlyOpenTestCase.values().subscribe(violationsPopover::rebind);

    }

    @Override
    public void afterParentInit() {
        initializeLanguageSelector();

        // languageVersionUiProperty is initialised

        ((ASTManagerImpl) astManager).languageVersionProperty().bind(languageVersionUIProperty.orElse(globalLanguageProperty().map(Language::getDefaultVersion)));

        handleTestOpenRequest(defaultTestCase, defaultTestCase);


        Var<String> areaText = Var.fromVal(
            latestValue(nodeEditionCodeArea.plainTextChanges()
                                           .successionEnds(AST_REFRESH_DELAY)
                                           .map(it -> nodeEditionCodeArea.getText())),
            text -> nodeEditionCodeArea.replaceText(text)
        );

        areaText.bindBidirectional(astManager.sourceCodeProperty());


        nodeEditionCodeArea.moveCaret(0, 0);

        initTreeView(astManager, astTreeView, editorTitledPane.errorMessageProperty());

        getDesignerRoot().registerService(DesignerRoot.RICH_TEXT_MAPPER, nodeEditionCodeArea);

        getService(DesignerRoot.TEST_LOADER)
            .messageStream(true, this)
            .subscribe(currentlyOpenTestCase::setValue);


        // this is to hide the toolbar when we're not in test case mode
        currentlyOpenTestCase.map(it -> true).orElseConst(false)
                             .values().distinct()
                             .subscribe(this::toggleTestEditMode);

        convertButtonVisibility.subscribe(visible -> convertToTestCaseButton.setVisible(visible));

    }

    private void toggleTestEditMode(boolean isTestCaseMode) {
        if (isTestCaseMode) {
            convertButtonVisibilitySource.push(false);

            AnchorPane pane = emptyPane();
            editorTitledPane.setContent(pane);

            AnchorPane otherPane = emptyPane();
            testCaseToolsTitledPane.setContent(otherPane);

            otherPane.getChildren().addAll(nodeEditionCodeArea);
            pane.getChildren().addAll(testCaseToolsTitledPane);
        } else {
            convertButtonVisibilitySource.push(true);
            AnchorPane otherPane = emptyPane();
            editorTitledPane.setContent(otherPane);
            otherPane.getChildren().addAll(nodeEditionCodeArea);
        }
    }

    private static AnchorPane emptyPane() {
        AnchorPane pane = new AnchorPane();
        pane.setPadding(Insets.EMPTY);
        return pane;
    }

    private void handleTestOpenRequest(@NonNull LiveTestCase oldValue, @NonNull LiveTestCase newValue) {
        oldValue.commitChanges();

        if (!newValue.getSource().equals(nodeEditionCodeArea.getText())) {
            nodeEditionCodeArea.replaceText(newValue.getSource());
        }

        if (newValue.getLanguageVersion() == null) {
            newValue.setLanguageVersion(globalLanguageProperty().getValue().getDefaultVersion());
        }

        Subscription sub = Subscription.multi(
            ReactfxUtil.rewireInit(newValue.sourceProperty(), astManager.sourceCodeProperty()),
            ReactfxUtil.rewireInit(newValue.languageVersionProperty(), languageVersionUIProperty)
        );

        newValue.addCommitHandler(t -> sub.unsubscribe());
    }


    private String getDefaultText() {
        try {
            // TODO this should take language into account
            //  it doesn't handle the case where java is not on the classpath

            return IOUtils.resourceToString(ResourceUtil.resolveResource("placeholders/editor.java"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "class Foo {\n"
                + "\n"
                + "    /*\n"
                + "        Welcome to the PMD Rule designer :)\n"
                + "\n"
                + "        Type some code in this area\n"
                + "        \n"
                + "        On the right, the Abstract Syntax Tree is displayed\n"
                + "        On the left, you can examine the XPath attributes of\n"
                + "        the nodes you select\n"
                + "        \n"
                + "        You can set the language you'd like to work in with\n"
                + "        the cog icon above this code area\n"
                + "     */\n"
                + "\n"
                + "    int i = 0;\n"
                + "}";
        }
    }


    private void initializeLanguageSelector() {

        languageVersionChoicebox.setConverter(DesignerUtil.stringConverter(LanguageVersion::getName, LanguageRegistryUtil::getLanguageVersionByName));

        getService(DesignerRoot.APP_GLOBAL_LANGUAGE)
            .values()
            .filter(Objects::nonNull)
            .subscribe(lang -> {
                languageVersionChoicebox.setItems(lang.getVersions().stream().sorted().collect(Collectors.toCollection(LiveArrayList::new)));
                languageVersionChoicebox.getSelectionModel().select(lang.getDefaultVersion());
                boolean disable = lang.getVersions().size() == 1;

                languageVersionChoicebox.setVisible(!disable);
                languageVersionChoicebox.setManaged(!disable);
            });


        languageVersionUIProperty = Var.suspendable(languageVersionChoicebox.valueProperty());
        // this will be overwritten by property restore if needed
        languageVersionUIProperty.setValue(defaultLanguageVersion());
    }


    public void showAuxclasspathSetupPopup() {
        new AuxclasspathSetupController(getDesignerRoot()).show(getMainStage(), auxclasspathFiles.getValue(), auxclasspathFiles::setValue);
    }


    public Var<List<Node>> currentRuleResultsProperty() {
        return nodeEditionCodeArea.currentRuleResultsProperty();
    }


    public Var<List<Node>> currentErrorNodesProperty() {
        return nodeEditionCodeArea.currentErrorNodesProperty();
    }


    public LanguageVersion getLanguageVersion() {
        return languageVersionUIProperty.getValue();
    }


    public void setLanguageVersion(LanguageVersion version) {
        languageVersionUIProperty.setValue(version);
    }


    public Var<LanguageVersion> languageVersionProperty() {
        return languageVersionUIProperty;
    }


    public String getText() {
        return nodeEditionCodeArea.getText();
    }


    public void setText(String expression) {
        nodeEditionCodeArea.replaceText(expression);
    }


    public Val<String> textProperty() {
        return Val.wrap(nodeEditionCodeArea.textProperty());
    }


    @PersistentProperty
    public List<File> getAuxclasspathFiles() {
        return auxclasspathFiles.getValue();
    }


    public void setAuxclasspathFiles(List<File> files) {
        auxclasspathFiles.setValue(files);
    }


    @Override
    public List<? extends SettingsOwner> getChildrenSettingsNodes() {
        return Collections.singletonList(defaultTestCase);
    }

    @Override
    public String getDebugName() {
        return "editor";
    }


    /**
     * Refreshes the AST and returns the new compilation unit if the parse didn't fail.
     */
    private static void initTreeView(ASTManager manager,
                                     AstTreeView treeView,
                                     Var<String> errorMessageProperty) {

        manager.sourceCodeProperty()
               .values()
               .filter(StringUtils::isBlank)
               .subscribe(code -> treeView.setAstRoot(null));

        manager.currentExceptionProperty()
               .values()
               .subscribe(e -> {
                   if (e == null) {
                       errorMessageProperty.setValue(null);
                   } else {
                       errorMessageProperty.setValue(sanitizeExceptionMessage(e));
                   }
               });

        manager.compilationUnitProperty()
               .values()
               .filter(Objects::nonNull)
               .subscribe(node -> {
                   errorMessageProperty.setValue("");
                   treeView.setAstRoot(node);
               });
    }

}
