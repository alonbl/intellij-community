// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.test.testFramework;

import com.intellij.lang.*;
import com.intellij.lang.impl.PsiBuilderFactoryImpl;
import com.intellij.mock.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.options.SchemeManagerFactory;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.pom.PomModel;
import com.intellij.pom.core.impl.PomModelImpl;
import com.intellij.pom.tree.TreeAspect;
import com.intellij.psi.*;
import com.intellij.psi.impl.*;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.MockSchemeManagerFactory;
import com.intellij.testFramework.TestDataFile;
import com.intellij.util.CachedValuesManagerImpl;
import com.intellij.util.Function;
import junit.framework.TestCase;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.KotlinFileType;
import org.picocontainer.ComponentAdapter;
import org.picocontainer.MutablePicoContainer;

import java.io.File;
import java.io.IOException;
import java.util.Set;

@SuppressWarnings("ALL")
public abstract class KtParsingTestCase extends KtPlatformLiteFixture {
    public static final Key<Document> HARD_REF_TO_DOCUMENT_KEY = Key.create("HARD_REF_TO_DOCUMENT_KEY");
    protected String myFilePrefix = "";
    protected String myFileExt;
    protected final String myFullDataPath;
    protected PsiFile myFile;
    private MockPsiManager myPsiManager;
    private PsiFileFactoryImpl myFileFactory;
    protected Language myLanguage;
    private final ParserDefinition[] myDefinitions;
    private final boolean myLowercaseFirstLetter;

    protected KtParsingTestCase(@NonNls @NotNull String dataPath, @NotNull String fileExt, @NotNull ParserDefinition... definitions) {
        this(dataPath, fileExt, false, definitions);
    }

    protected KtParsingTestCase(@NonNls @NotNull String dataPath, @NotNull String fileExt, boolean lowercaseFirstLetter, @NotNull ParserDefinition... definitions) {
        myDefinitions = definitions;
        myFullDataPath = getTestDataPath() + "/" + dataPath;
        myFileExt = fileExt;
        myLowercaseFirstLetter = lowercaseFirstLetter;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        initApplication();
        ComponentAdapter component = getApplication().getPicoContainer().getComponentAdapter(ProgressManager.class.getName());

        myProject = new MockProjectEx(getTestRootDisposable());
        myPsiManager = new MockPsiManager(myProject);
        myFileFactory = new PsiFileFactoryImpl(myPsiManager);
        MutablePicoContainer appContainer = getApplication().getPicoContainer();
        final MockEditorFactory editorFactory = new MockEditorFactory();
        MockFileTypeManager mockFileTypeManager = new MockFileTypeManager(KotlinFileType.INSTANCE);
        MockFileDocumentManagerImpl mockFileDocumentManager = new MockFileDocumentManagerImpl(
                HARD_REF_TO_DOCUMENT_KEY,
                new Function<CharSequence, Document>() {
                    @Override
                    public Document fun(CharSequence charSequence) {
                        return editorFactory
                                .createDocument(charSequence);
                    }
                }
        );

        registerApplicationService(PsiBuilderFactory.class, new PsiBuilderFactoryImpl());
        registerApplicationService(DefaultASTFactory.class, new DefaultASTFactoryImpl());
        registerApplicationService(SchemeManagerFactory.class, new MockSchemeManagerFactory());
        registerApplicationService(FileTypeManager.class, mockFileTypeManager);
        registerApplicationService(FileDocumentManager.class, mockFileDocumentManager);

        registerApplicationService(ProgressManager.class, new CoreProgressManager());

        registerComponentInstance(appContainer, FileTypeRegistry.class, mockFileTypeManager);
        registerComponentInstance(appContainer, FileTypeManager.class, mockFileTypeManager);
        registerComponentInstance(appContainer, EditorFactory.class, editorFactory);
        registerComponentInstance(appContainer, FileDocumentManager.class, mockFileDocumentManager);
        registerComponentInstance(appContainer, PsiDocumentManager.class, new MockPsiDocumentManager());

        myProject.registerService(PsiManager.class, myPsiManager);
        myProject.registerService(CachedValuesManager.class, new CachedValuesManagerImpl(myProject, new PsiCachedValuesFactory(myProject)));
        myProject.registerService(TreeAspect.class, new TreeAspect());

        for (ParserDefinition definition : myDefinitions) {
            addExplicitExtension(LanguageParserDefinitions.INSTANCE, definition.getFileNodeType().getLanguage(), definition);
        }
        if (myDefinitions.length > 0) {
            configureFromParserDefinition(myDefinitions[0], myFileExt);
        }

        // That's for reparse routines
        final PomModelImpl pomModel = new PomModelImpl(myProject);
        myProject.registerService(PomModel.class, pomModel);
    }

    public void configureFromParserDefinition(ParserDefinition definition, String extension) {
        myLanguage = definition.getFileNodeType().getLanguage();
        myFileExt = extension;
        addExplicitExtension(LanguageParserDefinitions.INSTANCE, this.myLanguage, definition);
        registerComponentInstance(
                getApplication().getPicoContainer(), FileTypeManager.class,
                new MockFileTypeManager(new MockLanguageFileType(myLanguage, myFileExt)));
    }

    protected <T> void addExplicitExtension(final LanguageExtension<T> instance, final Language language, final T object) {
        instance.addExplicitExtension(language, object);
        Disposer.register(myProject, new Disposable() {
            @Override
            public void dispose() {
                instance.removeExplicitExtension(language, object);
            }
        });
    }

    protected <T> void registerApplicationService(final Class<T> aClass, T object) {
        getApplication().registerService(aClass, object);
        Disposer.register(myProject, new Disposable() {
            @Override
            public void dispose() {
                getApplication().getPicoContainer().unregisterComponent(aClass.getName());
            }
        });
    }

    public MockProjectEx getProject() {
        return myProject;
    }

    public MockPsiManager getPsiManager() {
        return myPsiManager;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        myFile = null;
        myProject = null;
        myPsiManager = null;
    }

    protected String getTestDataPath() {
        return PathManager.getHomePath();
    }

    @NotNull
    public final String getTestName() {
        return getTestName(myLowercaseFirstLetter);
    }

    protected boolean includeRanges() {
        return false;
    }

    protected boolean skipSpaces() {
        return false;
    }

    protected boolean checkAllPsiRoots() {
        return true;
    }

    protected void doTest(boolean checkResult) {
        String name = getTestName();
        try {
            String text = loadFile(name + "." + myFileExt);
            myFile = createPsiFile(name, text);
            ensureParsed(myFile);
            assertEquals("light virtual file text mismatch", text, ((LightVirtualFile)myFile.getVirtualFile()).getContent().toString());
            assertEquals("virtual file text mismatch", text, LoadTextUtil.loadText(myFile.getVirtualFile()));
            assertEquals("doc text mismatch", text, myFile.getViewProvider().getDocument().getText());
            assertEquals("psi text mismatch", text, myFile.getText());
            ensureCorrectReparse(myFile);
            if (checkResult){
                checkResult(name, myFile);
            }
            else{
                toParseTreeText(myFile, skipSpaces(), includeRanges());
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void doTest(String suffix) throws IOException {
        String name = getTestName();
        String text = loadFile(name + "." + myFileExt);
        myFile = createPsiFile(name, text);
        ensureParsed(myFile);
        assertEquals(text, myFile.getText());
        checkResult(name + suffix, myFile);
    }

    protected void doCodeTest(String code) throws IOException {
        String name = getTestName();
        myFile = createPsiFile("a", code);
        ensureParsed(myFile);
        assertEquals(code, myFile.getText());
        checkResult(myFilePrefix + name, myFile);
    }

    protected PsiFile createPsiFile(String name, String text) {
        return createFile(name + "." + myFileExt, text);
    }

    protected PsiFile createFile(@NonNls String name, String text) {
        LightVirtualFile virtualFile = new LightVirtualFile(name, myLanguage, text);
        virtualFile.setCharset(CharsetToolkit.UTF8_CHARSET);
        return createFile(virtualFile);
    }

    protected PsiFile createFile(LightVirtualFile virtualFile) {
        return myFileFactory.trySetupPsiForFile(virtualFile, myLanguage, true, false);
    }

    protected void checkResult(@NonNls @TestDataFile String targetDataName, final PsiFile file) throws IOException {
        doCheckResult(myFullDataPath, file, checkAllPsiRoots(), targetDataName, skipSpaces(), includeRanges());
    }

    public static void doCheckResult(String testDataDir,
            PsiFile file,
            boolean checkAllPsiRoots,
            String targetDataName,
            boolean skipSpaces,
            boolean printRanges) throws IOException {
        FileViewProvider provider = file.getViewProvider();
        Set<Language> languages = provider.getLanguages();

        if (!checkAllPsiRoots || languages.size() == 1) {
            doCheckResult(testDataDir, targetDataName + ".txt", toParseTreeText(file, skipSpaces, printRanges).trim());
            return;
        }

        for (Language language : languages) {
            PsiFile root = provider.getPsi(language);
            String expectedName = targetDataName + "." + language.getID() + ".txt";
            doCheckResult(testDataDir, expectedName, toParseTreeText(root, skipSpaces, printRanges).trim());
        }
    }

    protected void checkResult(String actual) throws IOException {
        String name = getTestName();
        doCheckResult(myFullDataPath, myFilePrefix + name + ".txt", actual);
    }

    protected void checkResult(@TestDataFile @NonNls String targetDataName, String actual) throws IOException {
        doCheckResult(myFullDataPath, targetDataName, actual);
    }

    public static void doCheckResult(String fullPath, String targetDataName, String actual) throws IOException {
        String expectedFileName = fullPath + File.separatorChar + targetDataName;
        KtUsefulTestCase.assertSameLinesWithFile(expectedFileName, actual);
    }

    protected static String toParseTreeText(PsiElement file,  boolean skipSpaces, boolean printRanges) {
        return DebugUtil.psiToString(file, skipSpaces, printRanges);
    }

    protected String loadFile(@NonNls @TestDataFile String name) throws IOException {
        return loadFileDefault(myFullDataPath, name);
    }

    public static String loadFileDefault(String dir, String name) throws IOException {
        return FileUtil.loadFile(new File(dir, name), CharsetToolkit.UTF8, true).trim();
    }

    public static void ensureParsed(PsiFile file) {
        file.accept(new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                element.acceptChildren(this);
            }
        });
    }

    public static void ensureCorrectReparse(@NotNull PsiFile file) {
        String psiToStringDefault = DebugUtil.psiToString(file, false, false);
        String fileText = file.getText();
        DiffLog diffLog = (new BlockSupportImpl()).reparseRange(
                file, file.getNode(), TextRange.allOf(fileText), fileText, new EmptyProgressIndicator(), fileText);
        diffLog.performActualPsiChange(file);

        TestCase.assertEquals(psiToStringDefault, DebugUtil.psiToString(file, false, false));
    }
}
