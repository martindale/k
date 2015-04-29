// Copyright (c) 2014-2015 K Team. All Rights Reserved.

package org.kframework.kore.convertors;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import org.junit.Ignore;
import org.junit.Test;
import org.kframework.attributes.Source;
import org.kframework.backend.java.builtins.IntToken;
import org.kframework.backend.java.builtins.UninterpretedToken;
import org.kframework.backend.java.compile.KOREtoBackendKIL;
import org.kframework.backend.java.indexing.IndexingTable;
import org.kframework.backend.java.kil.BuiltinMap;
import org.kframework.backend.java.kil.ConstrainedTerm;
import org.kframework.backend.java.kil.Definition;
import org.kframework.backend.java.kil.GlobalContext;
import org.kframework.backend.java.kil.KItem;
import org.kframework.backend.java.kil.KLabelConstant;
import org.kframework.backend.java.kil.KList;
import org.kframework.backend.java.kil.KSequence;
import org.kframework.backend.java.kil.Sort;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.symbolic.BuiltinFunction;
import org.kframework.backend.java.symbolic.Equality;
import org.kframework.backend.java.symbolic.JavaExecutionOptions;
import org.kframework.backend.java.symbolic.JavaSymbolicCommonModule;
import org.kframework.backend.java.symbolic.SymbolicRewriter;
import org.kframework.backend.java.util.JavaKRunState;
import org.kframework.definition.Module;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.K;
import org.kframework.krun.api.KRunState;
import org.kframework.main.GlobalOptions;
import org.kframework.main.Tool;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;
import org.kframework.utils.inject.RequestScoped;
import org.kframework.utils.inject.SimpleScope;
import scala.collection.JavaConversions;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;


public class TstBackendOnKORE extends BaseTest {
    @Test
    public void kore_imp() throws IOException {
        sdfTest();
    }

    @Test @Ignore
    public void kore_csemantics() throws IOException, URISyntaxException {
        String filename = "/home/dwightguth/c-semantics/semantics/c11-translation.k";
        KExceptionManager kem = new KExceptionManager(new GlobalOptions());
        try {
            CompiledDefinition rwModuleAndProgramParser = new Kompile(FileUtil.testFileUtil(), kem).run(new File(filename),
                    "C11-TRANSLATION", "C11-TRANSLATION", "K");
            K program = rwModuleAndProgramParser.getProgramParser().apply("t(.Set, int) ==Type t(.Set, int)", Source.apply("generated by " + getClass().getSimpleName()));
            GetSymbolicRewriter getSymbolicRewriter = new GetSymbolicRewriter(rwModuleAndProgramParser.getCompiledExecutionModule()).invoke();
            KOREtoBackendKIL converter = new KOREtoBackendKIL(getSymbolicRewriter.termContext);
            getSymbolicRewriter.getRewriter().rewrite(new ConstrainedTerm(converter.convert(program), getSymbolicRewriter.termContext), getSymbolicRewriter.termContext.definition().context(), -1, false);
        } finally {
            kem.print();
        }
    }

    protected String convert(BaseTest.DefinitionWithContext defWithContext) {
        KILtoKORE kilToKore = new KILtoKORE(defWithContext.context);
        Module module = kilToKore.apply(defWithContext.definition).getModule("TEST").get();

        GetSymbolicRewriter getSymbolicRewriter = new GetSymbolicRewriter(module).invoke();
        Definition definition = getSymbolicRewriter.getDefinition();
        TermContext termContext = getSymbolicRewriter.getTermContext();
        SymbolicRewriter rewriter = getSymbolicRewriter.getRewriter();
        KSequence.Builder builder1 = KSequence.builder();
        builder1.concatenate(new KItem(
                KLabelConstant.of("'_/_", definition),
                KList.concatenate(
                        UninterpretedToken.of(Sort.of("Id"), "x1"),
                        UninterpretedToken.of(Sort.of("Id"), "x2")),
                Sort.of("AExp"),
                true));
        Term kContent = builder1.build();
        BuiltinMap.Builder builder2 = BuiltinMap.builder(termContext);
        builder2.put(UninterpretedToken.of(Sort.of("Id"), "x1"), IntToken.of(1));
        builder2.put(UninterpretedToken.of(Sort.of("Id"), "x2"), IntToken.of(0));
        Term stateContent = builder2.build();
        return ((JavaKRunState) rewriter.rewrite(
                new ConstrainedTerm(
                        new KItem(
                                KLabelConstant.of("'<top>", definition),
                                KList.concatenate(
                                        new KItem(
                                                KLabelConstant.of("'<k>", definition),
                                                KList.concatenate(kContent),
                                                Sort.of("KCell"),
                                                true),
                                        new KItem(
                                                KLabelConstant.of("'<state>", definition),
                                                KList.concatenate(stateContent),
                                                Sort.of("StateCell"),
                                                true)),
                        Sort.of("TopCell"),
                        true),
                termContext),
                null,
                -1,
                false))
                .getJavaKilTerm().toString();
    }

    @Override
    protected String expectedFilePostfix() {
        return "-backend-expected.txt";
    }

    private class GetSymbolicRewriter {
        private Module module;
        private Definition definition;
        private TermContext termContext;
        private SymbolicRewriter rewriter;

        public GetSymbolicRewriter(Module module) {
            this.module = module;
        }

        public Definition getDefinition() {
            return definition;
        }

        public TermContext getTermContext() {
            return termContext;
        }

        public SymbolicRewriter getRewriter() {
            return rewriter;
        }

        public GetSymbolicRewriter invoke() {
            definition = new Definition(module, null);

            SimpleScope requestScope = new SimpleScope();
            Injector injector = Guice.createInjector(new JavaSymbolicCommonModule() {
                @Override
                protected void configure() {
                    super.configure();
                    bind(GlobalOptions.class).toInstance(new GlobalOptions());
                    bind(Definition.class).toInstance(definition);
                    bind(Tool.class).toInstance(Tool.KRUN);

                    bindScope(RequestScoped.class, requestScope);
                    bind(SimpleScope.class).annotatedWith(Names.named("requestScope")).toInstance(requestScope);
                }
            });
            requestScope.enter();
            termContext = TermContext.of(new GlobalContext(
                    null,
                    new Equality.EqualityOperations(() -> definition, new JavaExecutionOptions()),
                    null,
                    new KItem.KItemOperations(Tool.KRUN, new JavaExecutionOptions(), null, () -> injector.getInstance(BuiltinFunction.class), new GlobalOptions()),
                    Tool.KRUN));
            termContext.global().setDefinition(definition);

            JavaConversions.setAsJavaSet(module.attributesFor().keySet()).stream()
                    .map(l -> KLabelConstant.of(l.name(), definition))
                    .forEach(definition::addKLabel);
            definition.addKoreRules(module, termContext);

            definition.setIndex(new IndexingTable(() -> definition, new IndexingTable.Data()));

            rewriter = new SymbolicRewriter(definition, new KompileOptions(), new JavaExecutionOptions(), new KRunState.Counter());
            requestScope.exit();
            return this;
        }
    }
}
