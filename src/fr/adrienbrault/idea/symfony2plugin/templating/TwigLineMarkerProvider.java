package fr.adrienbrault.idea.symfony2plugin.templating;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.ide.util.PsiElementListCellRenderer;
import com.intellij.navigation.GotoRelatedItem;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ConstantFunction;
import com.intellij.util.indexing.FileBasedIndex;
import com.jetbrains.php.PhpIcons;
import com.jetbrains.php.lang.psi.elements.Function;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.twig.TwigFile;
import com.jetbrains.twig.TwigFileType;
import com.jetbrains.twig.elements.TwigElementTypes;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.TwigHelper;
import fr.adrienbrault.idea.symfony2plugin.dic.RelatedPopupGotoLineMarker;
import fr.adrienbrault.idea.symfony2plugin.stubs.indexes.TwigIncludeStubIndex;
import fr.adrienbrault.idea.symfony2plugin.templating.dict.TemplateFileMap;
import fr.adrienbrault.idea.symfony2plugin.templating.util.TwigUtil;
import icons.TwigIcons;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class TwigLineMarkerProvider implements LineMarkerProvider {

    private TemplateFileMap templateMapCache = null;

    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> psiElements, @NotNull Collection<LineMarkerInfo> results) {

        if(psiElements.size() == 0 || !Symfony2ProjectComponent.isEnabled(psiElements.get(0))) {
            return;
        }

        for(PsiElement psiElement: psiElements) {

            // blocks
            if (TwigHelper.getBlockTagPattern().accepts(psiElement)) {

                LineMarkerInfo lineImpl = this.attachBlockImplements(psiElement);
                if(lineImpl != null) {
                    results.add(lineImpl);
                }

                LineMarkerInfo lineOverwrites = this.attachBlockOverwrites(psiElement);
                if(lineOverwrites != null) {
                    results.add(lineOverwrites);
                }

            }

            // controller
            if(psiElement instanceof TwigFile) {
                attachController((TwigFile) psiElement, results);

                // find foreign file references tags like:
                // include, embed, source, from, import, ...
                LineMarkerInfo lineIncludes = attachIncludes((TwigFile) psiElement);
                if(lineIncludes != null) {
                    results.add(lineIncludes);
                }

                // eg bundle overwrites
                LineMarkerInfo overwrites = attachOverwrites((TwigFile) psiElement);
                if(overwrites != null) {
                    results.add(overwrites);
                }
            }

        }

        // reset cache
        templateMapCache = null;

    }

    private void attachController(@NotNull TwigFile twigFile, @NotNull Collection<? super RelatedItemLineMarkerInfo> result) {

        Set<Function> methods = new HashSet<>();
        Method method = TwigUtil.findTwigFileController(twigFile);
        if(method != null) {
            methods.add(method);
        }

        methods.addAll(TwigUtil.getTwigFileMethodUsageOnIndex(twigFile));

        if(methods.size() == 0) {
            return;
        }

        NavigationGutterIconBuilder<PsiElement> builder = NavigationGutterIconBuilder.create(Symfony2Icons.TWIG_CONTROLLER_LINE_MARKER).
            setTargets(methods).
            setTooltipText("Navigate to controller");

        result.add(builder.createLineMarkerInfo(twigFile));
    }

    private LineMarkerInfo attachIncludes(@NotNull TwigFile twigFile) {
        TemplateFileMap files = getTemplateFilesByName(twigFile.getProject());

        Set<String> templateNames = TwigUtil.getTemplateName(twigFile.getVirtualFile(), files);

        boolean found = false;
        for(String templateName: templateNames) {
            Project project = twigFile.getProject();

            Collection<VirtualFile> containingFiles = FileBasedIndex.getInstance().getContainingFiles(
                TwigIncludeStubIndex.KEY, templateName, GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(project), TwigFileType.INSTANCE)
            );

            // stop on first target, we load them lazily afterwards
            if(containingFiles.size() > 0) {
                found = true;
                break;
            }
        }

        if(!found) {
            return null;
        }

        NavigationGutterIconBuilder<PsiElement> builder = NavigationGutterIconBuilder.create(PhpIcons.IMPLEMENTED)
            .setTargets(new MyTemplateIncludeLazyValue(twigFile, templateNames))
            .setTooltipText("Navigate to includes")
            .setCellRenderer(new MyFileReferencePsiElementListCellRenderer());

        return builder.createLineMarkerInfo(twigFile);
    }

    @Nullable
    private LineMarkerInfo attachOverwrites(@NotNull TwigFile twigFile) {

        Collection<PsiFile> targets = new ArrayList<>();

        TemplateFileMap files = getTemplateFilesByName(twigFile.getProject());

        for (String templateName: TwigUtil.getTemplateName(twigFile.getVirtualFile(), files)) {
            for (PsiFile psiFile : TwigHelper.getTemplatePsiElements(twigFile.getProject(), templateName)) {
                if(!psiFile.getVirtualFile().equals(twigFile.getVirtualFile()) && !targets.contains(psiFile)) {
                    targets.add(psiFile);
                }
            }
        }

        if(targets.size() == 0) {
            return null;
        }

        List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();
        for(PsiElement blockTag: targets) {
            gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(
                blockTag,
                TwigUtil.getPresentableTemplateName(files.getTemplates(), blockTag, true)
            ).withIcon(TwigIcons.TwigFileIcon, Symfony2Icons.TWIG_LINE_OVERWRITE));
        }

        return getRelatedPopover("Overwrites", "Overwrite", twigFile, gotoRelatedItems, Symfony2Icons.TWIG_LINE_OVERWRITE);
    }

    private TemplateFileMap getTemplateFilesByName(Project project) {
        return this.templateMapCache == null ? this.templateMapCache = TwigHelper.getTemplateMap(project, true, false) : this.templateMapCache;
    }

    private LineMarkerInfo getRelatedPopover(String singleItemTitle, String singleItemTooltipPrefix, PsiElement lineMarkerTarget, List<GotoRelatedItem> gotoRelatedItems) {
        return getRelatedPopover(singleItemTitle, singleItemTooltipPrefix, lineMarkerTarget, gotoRelatedItems, PhpIcons.IMPLEMENTED);
    }

    private LineMarkerInfo getRelatedPopover(String singleItemTitle, String singleItemTooltipPrefix, PsiElement lineMarkerTarget, List<GotoRelatedItem> gotoRelatedItems, Icon icon) {

        // single item has no popup
        String title = singleItemTitle;
        if(gotoRelatedItems.size() == 1) {
            String customName = gotoRelatedItems.get(0).getCustomName();
            if(customName != null) {
                title = String.format(singleItemTooltipPrefix, customName);
            }
        }

        return new LineMarkerInfo<>(
            lineMarkerTarget,
            lineMarkerTarget.getTextRange(),
            icon,
            6,
            new ConstantFunction<>(title),
            new RelatedPopupGotoLineMarker.NavigationHandler(gotoRelatedItems),
            GutterIconRenderer.Alignment.RIGHT
        );
    }

    @Nullable
    private LineMarkerInfo attachBlockImplements(final PsiElement psiElement) {
        PsiFile psiFile = psiElement.getContainingFile();
        if(psiFile == null) {
            return null;
        }

        TemplateFileMap files = getTemplateFilesByName(psiElement.getProject());

        Collection<PsiFile> twigChild = TwigUtil.getTemplateFileReferences(psiFile, files);
        if(twigChild.size() == 0) {
            return null;
        }

        final String blockName = psiElement.getText();

        List<PsiElement> blockTargets = new ArrayList<>();
        for(PsiFile psiFile1: twigChild) {

            blockTargets.addAll(Arrays.asList(PsiTreeUtil.collectElements(psiFile1, psiElement1 ->
                TwigHelper.getBlockTagPattern().accepts(psiElement1) && blockName.equals(psiElement1.getText())))
            );

        }

        if(blockTargets.size() == 0) {
            return null;
        }

        List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();
        for(PsiElement blockTag: blockTargets) {
            gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(blockTag, TwigUtil.getPresentableTemplateName(files.getTemplates(), blockTag, true)).withIcon(TwigIcons.TwigFileIcon, Symfony2Icons.TWIG_LINE_MARKER));
        }

        return getRelatedPopover("Implementations", "Impl: ", psiElement, gotoRelatedItems);

    }

    @Nullable
    private LineMarkerInfo attachBlockOverwrites(PsiElement psiElement) {

        PsiElement[] blocks = TwigTemplateGoToDeclarationHandler.getBlockGoTo(psiElement);
        if(blocks.length == 0) {
            return null;
        }

        List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();
        for(PsiElement blockTag: blocks) {
            gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(blockTag, TwigUtil.getPresentableTemplateName(getTemplateFilesByName(psiElement.getProject()).getTemplates(), blockTag, true)).withIcon(TwigIcons.TwigFileIcon, Symfony2Icons.TWIG_LINE_MARKER));
        }

        // single item has no popup
        String title = "Overwrites";
        if(gotoRelatedItems.size() == 1) {
            String customName = gotoRelatedItems.get(0).getCustomName();
            if(customName != null) {
                title = title.concat(": ").concat(customName);
            }
        }

        return new LineMarkerInfo<>(
            psiElement,
            psiElement.getTextRange(),
            PhpIcons.OVERRIDES, 6,
            new ConstantFunction<>(title),
            new RelatedPopupGotoLineMarker.NavigationHandler(gotoRelatedItems),
            GutterIconRenderer.Alignment.RIGHT
        );
    }

    @Nullable
    @Override
    public LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement psiElement) {
        return null;
    }

    private static class MyFileReferencePsiElementListCellRenderer extends PsiElementListCellRenderer {
        @Override
        public String getElementText(PsiElement psiElement) {
            String symbolPresentableText = SymbolPresentationUtil.getSymbolPresentableText(psiElement);
            return StringUtils.abbreviate(symbolPresentableText, 50);
        }

        @Nullable
        @Override
        protected String getContainerText(PsiElement psiElement, String s) {
            // relative path else fallback to default name extraction
            PsiFile containingFile = psiElement.getContainingFile();
            String relativePath = VfsUtil.getRelativePath(containingFile.getVirtualFile(), psiElement.getProject().getBaseDir(), '/');
            return relativePath != null ? relativePath : SymbolPresentationUtil.getSymbolContainerText(psiElement);
        }

        @Override
        protected int getIconFlags() {
            return 1;
        }

        @Override
        protected Icon getIcon(PsiElement psiElement) {
            if(psiElement.getNode().getElementType() == TwigElementTypes.INCLUDE_TAG) {
                return PhpIcons.IMPLEMENTED;
            } else if(psiElement.getNode().getElementType() == TwigElementTypes.EMBED_TAG) {
                return PhpIcons.OVERRIDEN;
            }

            return TwigIcons.TwigFileIcon;
        }
    }

    private static class MyTemplateIncludeLazyValue extends NotNullLazyValue<Collection<? extends PsiElement>> {
        @NotNull
        private final TwigFile twigFile;

        @NotNull
        private final Collection<String> templateNames;

        MyTemplateIncludeLazyValue(@NotNull TwigFile twigFile, @NotNull Collection<String> templateNames) {
            this.twigFile = twigFile;
            this.templateNames = templateNames;
        }

        @NotNull
        @Override
        protected Collection<? extends PsiElement> compute() {
            Collection<VirtualFile> twigFiles = new ArrayList<>();

            Project project = twigFile.getProject();

            for(String templateName: this.templateNames) {
                // collect files which contains given template name for inclusion
                twigFiles.addAll(FileBasedIndex.getInstance().getContainingFiles(
                    TwigIncludeStubIndex.KEY,
                    templateName,
                    GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(project), TwigFileType.INSTANCE))
                );
            }

            Collection<PsiElement> targets = new ArrayList<>();

            for (VirtualFile virtualFile : twigFiles) {
                // resolve virtual file
                PsiFile myTwigFile = PsiManager.getInstance(project).findFile(virtualFile);
                if(!(myTwigFile instanceof TwigFile)) {
                    continue;
                }

                Collection<PsiElement> fileTargets = new ArrayList<>();

                TwigUtil.visitTemplateIncludes((TwigFile) myTwigFile, templateInclude -> {
                        if(this.templateNames.contains(templateInclude.getTemplateName()) || this.templateNames.contains(TwigHelper.normalizeTemplateName(templateInclude.getTemplateName()))) {
                            fileTargets.add(templateInclude.getPsiElement());
                        }
                    }
                );

                // navigate to include pattern; else fallback to file scope
                if(fileTargets.size() > 0) {
                    targets.addAll(fileTargets);
                } else {
                    targets.add(myTwigFile);
                }
            }

            return targets;
        }
    }
}
