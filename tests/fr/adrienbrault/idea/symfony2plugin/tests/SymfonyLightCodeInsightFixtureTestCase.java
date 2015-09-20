package fr.adrienbrault.idea.symfony2plugin.tests;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.codeInspection.*;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FileBasedIndexImpl;
import com.intellij.util.indexing.ID;
import com.jetbrains.php.lang.psi.elements.Function;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpReference;
import fr.adrienbrault.idea.symfony2plugin.Settings;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public abstract class SymfonyLightCodeInsightFixtureTestCase extends LightCodeInsightFixtureTestCase {

    @Override
    public void setUp() throws Exception {
        super.setUp();
        Settings.getInstance(myFixture.getProject()).pluginEnabled = true;
    }

    public void assertCompletionContains(LanguageFileType languageFileType, String configureByText, String... lookupStrings) {

        myFixture.configureByText(languageFileType, configureByText);
        myFixture.completeBasic();

        checkContainsCompletion(lookupStrings);
    }

    public void assertAtTextCompletionContains(String findByText, String... lookupStrings) {

        final PsiElement element = myFixture.findElementByText(findByText, PsiElement.class);
        assert element != null : "No element found by text: " + findByText;
        myFixture.getEditor().getCaretModel().moveToOffset(element.getTextOffset() + 1);
        myFixture.completeBasic();

        checkContainsCompletion(lookupStrings);
    }

    public void assertCompletionNotContains(LanguageFileType languageFileType, String configureByText, String... lookupStrings) {

        myFixture.configureByText(languageFileType, configureByText);
        myFixture.completeBasic();

        assertFalse(myFixture.getLookupElementStrings().containsAll(Arrays.asList(lookupStrings)));
    }

    public void assertCompletionContains(String filename, String configureByText, String... lookupStrings) {

        myFixture.configureByText(filename, configureByText);
        myFixture.completeBasic();

        completionContainsAssert(lookupStrings);
    }

    private void completionContainsAssert(String[] lookupStrings) {
        List<String> lookupElements = myFixture.getLookupElementStrings();
        for (String s : Arrays.asList(lookupStrings)) {
            if(!lookupElements.contains(s)) {
                fail(String.format("failed that completion contains %s in %s", s, lookupElements.toString()));
            }
        }
    }

    public void assertNavigationContains(LanguageFileType languageFileType, String configureByText, String targetShortcut) {
        myFixture.configureByText(languageFileType, configureByText);
        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNavigationContains(psiElement, targetShortcut);
    }

    public void assertNavigationContains(PsiElement psiElement, String targetShortcut) {

        if(!targetShortcut.startsWith("\\")) {
            targetShortcut = "\\" + targetShortcut;
        }

        Set<String> classTargets = new HashSet<String>();

        for (GotoDeclarationHandler gotoDeclarationHandler : Extensions.getExtensions(GotoDeclarationHandler.EP_NAME)) {
            PsiElement[] gotoDeclarationTargets = gotoDeclarationHandler.getGotoDeclarationTargets(psiElement, 0, myFixture.getEditor());
            if(gotoDeclarationTargets != null && gotoDeclarationTargets.length > 0) {

                for (PsiElement gotoDeclarationTarget : gotoDeclarationTargets) {
                    if(gotoDeclarationTarget instanceof Method) {

                        String meName = ((Method) gotoDeclarationTarget).getName();

                        String clName = ((Method) gotoDeclarationTarget).getContainingClass().getPresentableFQN();
                        if(!clName.startsWith("\\")) {
                            clName = "\\" + clName;
                        }

                        classTargets.add(clName + "::" + meName);
                    } else if(gotoDeclarationTarget instanceof Function) {
                        classTargets.add("\\" + ((Function) gotoDeclarationTarget).getName());
                    }
                }

            }
        }

        if(!classTargets.contains(targetShortcut)) {
            fail(String.format("failed that PsiElement (%s) navigate to %s on %s", psiElement.toString(), targetShortcut, classTargets.toString()));
        }

    }

    public void assertNavigationMatchWithParent(LanguageFileType languageFileType, String configureByText, IElementType iElementType) {
        assertNavigationMatch(languageFileType, configureByText, PlatformPatterns.psiElement().withParent(PlatformPatterns.psiElement(iElementType)));
    }

    public void assertNavigationMatch(String filename, String configureByText, ElementPattern<?> pattern) {
        myFixture.configureByText(filename, configureByText);
        assertNavigationMatch(pattern);
    }

    public void assertNavigationMatch(LanguageFileType languageFileType, String configureByText, ElementPattern<?> pattern) {
        myFixture.configureByText(languageFileType, configureByText);
        assertNavigationMatch(pattern);
    }

    private void assertNavigationMatch(ElementPattern<?> pattern) {

        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        Set<String> targetStrings = new HashSet<String>();

        for (GotoDeclarationHandler gotoDeclarationHandler : Extensions.getExtensions(GotoDeclarationHandler.EP_NAME)) {

            PsiElement[] gotoDeclarationTargets = gotoDeclarationHandler.getGotoDeclarationTargets(psiElement, 0, myFixture.getEditor());
            if(gotoDeclarationTargets == null || gotoDeclarationTargets.length == 0) {
                continue;
            }

            for (PsiElement gotoDeclarationTarget : gotoDeclarationTargets) {
                targetStrings.add(gotoDeclarationTarget.toString());
                if(pattern.accepts(gotoDeclarationTarget)) {
                    return;
                }
            }
        }

        fail(String.format("failed that PsiElement (%s) navigate matches one of %s", psiElement.toString(), targetStrings.toString()));
    }

    public void assertNavigationContainsFile(LanguageFileType languageFileType, String configureByText, String targetShortcut) {
        myFixture.configureByText(languageFileType, configureByText);
        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        Set<String> targets = new HashSet<String>();

        for (GotoDeclarationHandler gotoDeclarationHandler : Extensions.getExtensions(GotoDeclarationHandler.EP_NAME)) {
            PsiElement[] gotoDeclarationTargets = gotoDeclarationHandler.getGotoDeclarationTargets(psiElement, 0, myFixture.getEditor());
            if (gotoDeclarationTargets != null && gotoDeclarationTargets.length > 0) {
                for (PsiElement gotoDeclarationTarget : gotoDeclarationTargets) {
                    if(gotoDeclarationTarget instanceof PsiFile) {
                        targets.add(((PsiFile) gotoDeclarationTarget).getVirtualFile().getUrl());
                    }
                }
            }
        }

        // its possible to have memory fields,
        // so simple check for ending conditions
        // temp:///src/interchange.en.xlf
        for (String target : targets) {
            if(target.endsWith(targetShortcut)) {
                return;
            }
        }

        fail(String.format("failed that PsiElement (%s) navigate to file %s", psiElement.toString(), targetShortcut));
    }

    public void assertCompletionLookupTailEquals(LanguageFileType languageFileType, String configureByText, String lookupString, String tailText) {

        myFixture.configureByText(languageFileType, configureByText);
        myFixture.completeBasic();

        for (LookupElement lookupElement : myFixture.getLookupElements()) {

            if(!lookupElement.getLookupString().equals(lookupString)) {
                continue;
            }

            LookupElementPresentation presentation = new LookupElementPresentation();
            lookupElement.renderElement(presentation);

            if(presentation.getTailText() == null) {
                fail(String.format("failed to check '%s'", lookupString));
            }

            if(!presentation.getTailText().equals(tailText)) {
                fail(String.format("failed that on '%s' '%s' is equal '%s'", lookupString, tailText, presentation.getTailText()));
            }

            return;

        }

        fail(String.format("failed to check '%s' because it's unknown", lookupString));
    }

    public void assertPhpReferenceResolveTo(LanguageFileType languageFileType, String configureByText, ElementPattern<?> pattern) {
        myFixture.configureByText(languageFileType, configureByText);
        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        psiElement = PsiTreeUtil.getParentOfType(psiElement, PhpReference.class);
        if (psiElement == null) {
            fail("Element is not PhpReference.");
        }

        assertTrue(pattern.accepts(((PhpReference) psiElement).resolve()));
    }

    public void assertPhpReferenceNotResolveTo(LanguageFileType languageFileType, String configureByText, ElementPattern<?> pattern) {
        myFixture.configureByText(languageFileType, configureByText);
        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        psiElement = PsiTreeUtil.getParentOfType(psiElement, PhpReference.class);
        if (psiElement == null) {
            fail("Element is not PhpReference.");
        }

        assertFalse(pattern.accepts(((PhpReference) psiElement).resolve()));
    }

    public void assertPhpReferenceSignatureEquals(LanguageFileType languageFileType, String configureByText, String typeSignature) {
        myFixture.configureByText(languageFileType, configureByText);
        PsiElement psiElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        psiElement = PsiTreeUtil.getParentOfType(psiElement, PhpReference.class);
        if (!(psiElement instanceof PhpReference)) {
            fail("Element is not PhpReference.");
        }

        assertEquals(typeSignature, ((PhpReference) psiElement).getSignature());
    }

    public void assertCompletionResultEquals(String filename, String complete, String result) {
            myFixture.configureByText(filename, complete);
            myFixture.completeBasic();
            myFixture.checkResult(result);
    }

    public void assertCompletionResultEquals(LanguageFileType languageFileType, String complete, String result) {
        myFixture.configureByText(languageFileType, complete);
        myFixture.completeBasic();
        myFixture.checkResult(result);
    }

    public void assertCheckHighlighting(String filename, String result) {
        myFixture.configureByText(filename, result);
        myFixture.checkHighlighting();
    }

    public void assertIndexContains(@NotNull ID<String, ?> id, @NotNull String... keys) {
        assertIndex(id, false, keys);
    }

    public void assertIndexNotContains(@NotNull ID<String, ?> id, @NotNull String... keys) {
        assertIndex(id, true, keys);
    }

    public void assertIndex(@NotNull ID<String, ?> id, boolean notCondition, @NotNull String... keys) {
        for (String key : keys) {

            final Collection<VirtualFile> virtualFiles = new ArrayList<VirtualFile>();

            FileBasedIndexImpl.getInstance().getFilesWithKey(id, new HashSet<String>(Arrays.asList(key)), new Processor<VirtualFile>() {
                @Override
                public boolean process(VirtualFile virtualFile) {
                    virtualFiles.add(virtualFile);
                    return true;
                }
            }, GlobalSearchScope.allScope(getProject()));

            if(notCondition && virtualFiles.size() > 0) {
                fail(String.format("Fail that ID '%s' not contains '%s'", id.toString(), key));
            } else if(!notCondition && virtualFiles.size() == 0) {
                fail(String.format("Fail that ID '%s' contains '%s'", id.toString(), key));
            }
        }
    }

    public void assertIndexContainsKeyWithValue(@NotNull ID<String, String> id, @NotNull String key, @NotNull String value) {
        assertContainsElements(FileBasedIndexImpl.getInstance().getValues(id, key, GlobalSearchScope.allScope(getProject())), value);
    }

    public void assertLocalInspectionContainsContains(String filename, String content, String contains) {
        Set<String> matches = new HashSet<String>();

        Pair<List<ProblemDescriptor>, Integer> localInspectionsAtCaret = getLocalInspectionsAtCaret(filename, content);
        for (ProblemDescriptor result : localInspectionsAtCaret.getFirst()) {
            TextRange textRange = result.getPsiElement().getTextRange();
            if (textRange.contains(localInspectionsAtCaret.getSecond()) && result.toString().equals(contains)) {
                return;
            }

            matches.add(result.toString());
        }

        fail(String.format("Fail matches '%s' with one of %s", contains, matches));
    }

    public void assertLocalInspectionContainsNotContains(String filename, String content, String contains) {
        Pair<List<ProblemDescriptor>, Integer> localInspectionsAtCaret = getLocalInspectionsAtCaret(filename, content);

        for (ProblemDescriptor result : localInspectionsAtCaret.getFirst()) {
            TextRange textRange = result.getPsiElement().getTextRange();
            if (textRange.contains(localInspectionsAtCaret.getSecond())) {
                fail(String.format("Fail inspection not contains '%s'", contains));
            }
        }
    }

    private Pair<List<ProblemDescriptor>, Integer> getLocalInspectionsAtCaret(String filename, String content) {

        PsiElement psiFile = myFixture.configureByText(filename, content);

        int caretOffset = myFixture.getCaretOffset();
        if(caretOffset <= 0) {
            fail("Please provide <caret> tag");
        }

        ProblemsHolder problemsHolder = new ProblemsHolder(InspectionManager.getInstance(getProject()), psiFile.getContainingFile(), false);

        for (LocalInspectionEP localInspectionEP : LocalInspectionEP.LOCAL_INSPECTION.getExtensions()) {
            Object object = localInspectionEP.getInstance();
            if(!(object instanceof LocalInspectionTool)) {
                continue;
            }

            ((LocalInspectionTool) object).buildVisitor(problemsHolder, false);
        }

        return new Pair<List<ProblemDescriptor>, Integer>(problemsHolder.getResults(), caretOffset);
    }

    protected void assertLocalInspectionIsEmpty(String filename, String content) {
        Pair<List<ProblemDescriptor>, Integer> localInspectionsAtCaret = getLocalInspectionsAtCaret(filename, content);

        for (ProblemDescriptor result : localInspectionsAtCaret.getFirst()) {
            TextRange textRange = result.getPsiElement().getTextRange();
            if (textRange.contains(localInspectionsAtCaret.getSecond())) {
                fail("Fail that matches is empty");
            }
        }
    }

    protected void createDummyFiles(String... files) throws Exception {
        for (String file : files) {
            String path = myFixture.getProject().getBaseDir().getPath() + "/" + file;
            File f = new File(path);
            f.getParentFile().mkdirs();
            f.createNewFile();
        }
    }

    private void checkContainsCompletion(String[] lookupStrings) {
        completionContainsAssert(lookupStrings);
    }

}
