// Copyright (c) 2016 K Team. All Rights Reserved.
package org.kframework;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.tuple.Pair;
import org.kframework.RewriterResult;
import org.kframework.attributes.Att;
import org.kframework.attributes.Source;
import org.kframework.backend.java.compile.KOREtoBackendKIL;
import org.kframework.backend.java.kil.BuiltinList;
import org.kframework.backend.java.kil.ConstrainedTerm;
import org.kframework.backend.java.kil.GlobalContext;
import org.kframework.backend.java.kil.KItem;
import org.kframework.backend.java.kil.KLabelConstant;
import org.kframework.backend.java.kil.KList;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.frontend.compile.ExpandMacros;
import org.kframework.backend.java.symbolic.ConjunctiveFormula;
import org.kframework.backend.java.symbolic.InitializeRewriter;
import org.kframework.backend.java.symbolic.JavaBackend;
import org.kframework.backend.java.symbolic.JavaExecutionOptions;
import org.kframework.backend.java.symbolic.MacroExpander;
import org.kframework.backend.java.symbolic.ProofExecutionMode;
import org.kframework.backend.java.symbolic.Stage;
import org.kframework.backend.java.symbolic.SymbolicRewriter;
import org.kframework.backend.java.util.JavaKRunState;
import org.kframework.compile.NormalizeKSeq;
import org.kframework.definition.Definition;
import org.kframework.definition.Module;
import org.kframework.definition.Rule;
import org.kframework.kil.Attribute;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kompile.KompileOptions;
import org.kframework.frontend.K;
import org.kframework.krun.KRun;
import org.kframework.krun.KRunOptions;
import org.kframework.krun.api.KRunState;
import org.kframework.krun.api.io.FileSystem;
import org.kframework.krun.ioserver.filesystem.portable.PortableFileSystem;
import org.kframework.main.GlobalOptions;
import org.kframework.parser.concrete2kore.generator.RuleGrammarGenerator;
import org.kframework.rewriter.Rewriter;
import org.kframework.utils.Stopwatch;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;
import org.kframework.utils.options.SMTOptions;
import scala.Tuple2;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.kframework.Collections.*;

/**
 * KRunAPI
 *
 * Example usage:
 *
 *   String def = FileUtil.load(new File(args[0])); // "require \"domains.k\" module A syntax KItem ::= \"run\" rule run => ... endmodule"
 *   String mod = args[1]; // "A"
 *   String pgm = FileUtil.load(new File(args[2])); // "run"
 *
 *   String prove = args[3]; // *_spec.k
 *   String prelude = args[4]; // *.smt2
 *
 *   // kompile
 *   CompiledDefinition compiledDef = kapi.kompile(def, mod);
 *
 *   // krun
 *   RewriterResult result = kapi.krun(pgm, null, compiledDef);
 *   kprint(compiledDef, result);
 *
 *   // kprove
 *   kapi.kprove(prove, prelude, compiledDef);
 *
 */
public class Kapi {

    public KapiGlobal kapiGlobal;

    public Kapi(KapiGlobal kapiGlobal) {
        this.kapiGlobal = kapiGlobal;
    }

    public Kapi() {
        this(new KapiGlobal());
    }

    public CompiledDefinition kompile(String def, String mainModuleName) {
        return kompile(def, mainModuleName, DefinitionParser.defaultLookupDirectories());
    }

    public CompiledDefinition kompile(String def, String mainModuleName, List<File> lookupDirectories) {
        // parse
        Definition parsedDef = DefinitionParser.from(def, mainModuleName, lookupDirectories);

        // compile (translation pipeline)
        Function<Definition, Definition> pipeline = new JavaBackend(kapiGlobal).steps();
        CompiledDefinition compiledDef = new Kompile(kapiGlobal).compile(parsedDef, pipeline);

        return compiledDef;
    }

    public K kast(String programText, CompiledDefinition compiledDef) {
        // parse program
        BiFunction<String, Source, K> programParser = compiledDef.getProgramParser(kapiGlobal.kem);
        K pgm = programParser.apply(programText, Source.apply("generated by api"));

        // put into configuration
        K program = KRun.getInitConfig(pgm, compiledDef, kapiGlobal.kem);

        /* TODO: figure out if it is needed
        program = new KTokenVariablesToTrueVariables()
                .apply(compiledDef.kompiledDefinition.getModule(compiledDef.mainSyntaxModuleName()).get(), program);
        */

        return program;
    }

    public RewriterResult krun(String programText, Integer depth, CompiledDefinition compiledDef) {

        K program = kast(programText, compiledDef);

        RewriterResult result = rewrite(program, compiledDef, depth);

        return result;
    }

    // rewrite up to the given depth
    public RewriterResult rewrite(K program, CompiledDefinition compiledDef, Integer depth) {
        // initialize rewriter
        Tuple2<SymbolicRewriter,TermContext> tuple = getRewriter(compiledDef);
        SymbolicRewriter rewriter = tuple._1();
        TermContext rewritingContext = tuple._2();
        KOREtoBackendKIL converter = rewriter.getConstructor();

        // prepare term to rewrite  // TODO: simplify
        Term programInKIL = converter.convert(program); // conversion: kore -> kil
        Term programWithMacroExpanded = MacroExpander.expandAndEvaluate(rewritingContext, kapiGlobal.kem, programInKIL); // macro expansion
        ConstrainedTerm programInConstrainedTerm = new ConstrainedTerm(programWithMacroExpanded, rewritingContext); // initial constrained term

        // rewrite: apply the rewriter to the term
        JavaKRunState result = (JavaKRunState) rewriter.rewrite(programInConstrainedTerm, Optional.ofNullable(depth).orElse(-1));
        RewriterResult resultInScala = new RewriterResult(result.getStepsTaken(), result.getJavaKilTerm());

        return resultInScala;
    }

    public Tuple2<SymbolicRewriter,TermContext> getRewriter(CompiledDefinition compiledDef) {
        // TODO: simplify. skip to read.

        Map<String, MethodHandle> hookProvider = HookProvider.get(kapiGlobal.kem);
        InitializeRewriter.InitializeDefinition initializeDefinition = new InitializeRewriter.InitializeDefinition();

        GlobalContext initializingContextGlobal = new GlobalContext(kapiGlobal, hookProvider, Stage.INITIALIZING);
        TermContext initializingContext = TermContext.builder(initializingContextGlobal).freshCounter(0).build();
        org.kframework.backend.java.kil.Definition evaluatedDef = initializeDefinition.invoke(compiledDef.executionModule(), kapiGlobal.kem, initializingContext.global());

        GlobalContext rewritingContextGlobal = new GlobalContext(kapiGlobal, hookProvider, Stage.REWRITING);
        rewritingContextGlobal.setDefinition(evaluatedDef);
        TermContext rewritingContext = TermContext.builder(rewritingContextGlobal).freshCounter(initializingContext.getCounterValue()).build();

        KOREtoBackendKIL converter = new KOREtoBackendKIL(compiledDef.executionModule(), evaluatedDef, rewritingContext.global(), false);

        SymbolicRewriter rewriter = new SymbolicRewriter(rewritingContextGlobal, kapiGlobal.kompileOptions.transition, new KRunState.Counter(), converter); // TODO:DROP

        return Tuple2.apply(rewriter, rewritingContext);
    }

    @Deprecated
    private RewriterResult rewriteOrig(K program, CompiledDefinition compiledDef, Integer depth) {
        Map<String, MethodHandle> hookProvider = HookProvider.get(kapiGlobal.kem);
        InitializeRewriter.InitializeDefinition initializeDefinition = new InitializeRewriter.InitializeDefinition();
        //
        Rewriter rewriter = (InitializeRewriter.SymbolicRewriterGlue)
                new InitializeRewriter(kapiGlobal,
                        hookProvider,
                        initializeDefinition)
                        .apply(Pair.of(compiledDef.executionModule(), null));
        //
        RewriterResult result = ((InitializeRewriter.SymbolicRewriterGlue) rewriter).execute(program, Optional.ofNullable(depth));
        return result;
    }

    public static void kprint(CompiledDefinition compiledDef, RewriterResult result) {
        GlobalOptions globalOptions = new GlobalOptions();
        KRunOptions krunOptions = new KRunOptions();
        FileUtil files = FileUtil.get(globalOptions, System.getenv());

        // print output
        // from org.kframework.krun.KRun.run()
        KRun.prettyPrint(compiledDef, krunOptions.output, s -> KRun.outputFile(s, krunOptions, files), result.k());
    }

    /**
     * compiledDef0: for parsing spec rules
     * compiledDef1: for symbolic execution
     * compiledDef2: for symbolic execution
     */
    public static void kequiv(CompiledDefinition compiledDef0, CompiledDefinition compiledDef1, CompiledDefinition compiledDef2, String proofFile1, String proofFile2, String prelude) {

        GlobalContext global = getGlobal(compiledDef0);
        Info info1 = getInfo(compiledDef1, proofFile1, prelude);
        Info info2 = getInfo(compiledDef2, proofFile2, prelude);

        java.util.List<ConjunctiveFormula> startEnsures = new ArrayList<>();
        assert info1.startEnsures.size() == info2.startEnsures.size();
        for (int i = 0; i < info1.startEnsures.size(); i++) {
            startEnsures.add(getConjunctiveFormula(info1.startEnsures.get(i), info2.startEnsures.get(i), global));
        }

        java.util.List<ConjunctiveFormula> targetEnsures = new ArrayList<>();
        assert info1.targetEnsures.size() == info2.targetEnsures.size();
        for (int i = 0; i < info1.targetEnsures.size(); i++) {
            targetEnsures.add(getConjunctiveFormula(info1.targetEnsures.get(i), info2.targetEnsures.get(i), global));
        }

        boolean result = EquivChecker.equiv(
                info1.startSyncNodes, info2.startSyncNodes,
                info1.targetSyncNodes, info2.targetSyncNodes,
                startEnsures, //info1.startEnsures, info2.startEnsures,
                targetEnsures, //info1.targetEnsures, info2.targetEnsures,
                info1.trusted, info2.trusted,
                info1.rewriter, info2.rewriter);

        System.out.println(result);

        return;
    }

    private static ConjunctiveFormula getConjunctiveFormula(ConjunctiveFormula e1, ConjunctiveFormula e2, GlobalContext global) {

        ConjunctiveFormula ensure = ConjunctiveFormula.of(global);

        ImmutableList<Term> l1 = getChildren(e1);
        ImmutableList<Term> l2 = getChildren(e2);

        assert l1.size() == l2.size();
        for (int j = 0; j < l1.size(); j++) {
            // TODO: make it better
            ensure = ensure.add(
                    ((KList) ((KItem) l1.get(j)).kList()).getContents().get(0),
                    ((KList) ((KItem) l2.get(j)).kList()).getContents().get(0));
        }

        return ensure;
    }

    private static ImmutableList<Term> getChildren(ConjunctiveFormula e) {
        // TODO: make it better
        assert e.equalities().size() == 1;
        assert e.equalities().get(0).leftHandSide() instanceof KItem;
        assert ((KItem) e.equalities().get(0).leftHandSide()).kLabel() instanceof KLabelConstant;
        assert ((KLabelConstant) ((KItem) e.equalities().get(0).leftHandSide()).kLabel()).label().equals("vars");
        assert ((KItem) e.equalities().get(0).leftHandSide()).kList() instanceof KList;
        assert ((KList) ((KItem) e.equalities().get(0).leftHandSide()).kList()).getContents().size() == 1;
        assert ((KList) ((KItem) e.equalities().get(0).leftHandSide()).kList()).getContents().get(0) instanceof BuiltinList;

        return ((BuiltinList) ((KList) ((KItem) e.equalities().get(0).leftHandSide()).kList()).getContents().get(0)).children;
    }

    // TODO: better name
    public static class Info {
        public java.util.List<ConstrainedTerm> startSyncNodes;
        public java.util.List<ConstrainedTerm> targetSyncNodes;
        public java.util.List<ConjunctiveFormula> startEnsures;
        public java.util.List<ConjunctiveFormula> targetEnsures;
        public java.util.List<Boolean> trusted;
        public SymbolicRewriter rewriter;

        public Info(
                java.util.List<ConstrainedTerm> startSyncNodes,
                java.util.List<ConstrainedTerm> targetSyncNodes,
                java.util.List<ConjunctiveFormula> startEnsures,
                java.util.List<ConjunctiveFormula> targetEnsures,
                java.util.List<Boolean> trusted,
                SymbolicRewriter rewriter
        ) {
            this.startSyncNodes = startSyncNodes;
            this.targetSyncNodes = targetSyncNodes;
            this.startEnsures = startEnsures;
            this.targetEnsures = targetEnsures;
            this.trusted = trusted;
            this.rewriter = rewriter;
        }
    }

    public static Info getInfo(CompiledDefinition compiledDef, String proofFile, String prelude) {

        GlobalOptions globalOptions = new GlobalOptions();
        KompileOptions kompileOptions = new KompileOptions();
        KRunOptions krunOptions = new KRunOptions();
        JavaExecutionOptions javaExecutionOptions = new JavaExecutionOptions();

        KExceptionManager kem = new KExceptionManager(globalOptions);
        Stopwatch sw = new Stopwatch(globalOptions);
        FileUtil files = FileUtil.get(globalOptions, System.getenv());

        FileSystem fs = new PortableFileSystem(kem, files);
        Map<String, MethodHandle> hookProvider = HookProvider.get(kem); // new HashMap<>();
        InitializeRewriter.InitializeDefinition initializeDefinition = new InitializeRewriter.InitializeDefinition();

        //// setting options

        krunOptions.experimental.prove = proofFile;
        krunOptions.experimental.smt.smtPrelude = prelude;

        SMTOptions smtOptions = krunOptions.experimental.smt;

        //// parse spec file

        Kompile kompile = new Kompile(kompileOptions, globalOptions, files, kem, sw, false);
        Module specModule = kompile.parseModule(compiledDef, files.resolveWorkingDirectory(proofFile).getAbsoluteFile());

        scala.collection.Set<Module> alsoIncluded = Stream.of("K-TERM", "K-REFLECTION", RuleGrammarGenerator.ID_PROGRAM_PARSING)
                .map(mod -> compiledDef.getParsedDefinition().getModule(mod).get())
                .collect(org.kframework.Collections.toSet());

        specModule = new JavaBackend(kem, files, globalOptions, kompileOptions)
                .stepsForProverRules()
                .apply(Definition.apply(specModule, org.kframework.Collections.<Module>add(specModule, alsoIncluded), Att.apply()))
                .getModule(specModule.name()).get();

        ExpandMacros macroExpander = new ExpandMacros(compiledDef.executionModule(), kem, files, globalOptions, kompileOptions.transition, kompileOptions.experimental.smt);

        List<Rule> specRulesKORE = stream(specModule.localRules())
                .filter(r -> r.toString().contains("spec.k"))
                .map(r -> (Rule) macroExpander.expand(r))
                .map(r -> ProofExecutionMode.transformFunction(JavaBackend::ADTKVariableToSortedVariable, r))
                .map(r -> ProofExecutionMode.transformFunction(Kompile::convertKSeqToKApply, r))
                .map(r -> ProofExecutionMode.transform(NormalizeKSeq.self(), r))
                        //.map(r -> kompile.compileRule(compiledDefinition, r))
                .collect(Collectors.toList());

        //// creating rewritingContext

        GlobalContext initializingContextGlobal = new GlobalContext(fs, false, globalOptions, krunOptions, kem, smtOptions, hookProvider, files, Stage.INITIALIZING);
        TermContext initializingContext = TermContext.builder(initializingContextGlobal).freshCounter(0).build();
        org.kframework.backend.java.kil.Definition evaluatedDef = initializeDefinition.invoke(compiledDef.executionModule(), kem, initializingContext.global());

        GlobalContext rewritingContextGlobal = new GlobalContext(fs, false, globalOptions, krunOptions, kem, smtOptions, hookProvider, files, Stage.REWRITING);
        rewritingContextGlobal.setDefinition(evaluatedDef);
        TermContext rewritingContext = TermContext.builder(rewritingContextGlobal).freshCounter(initializingContext.getCounterValue()).build();

        //// massage spec rules

        KOREtoBackendKIL converter = new KOREtoBackendKIL(compiledDef.executionModule(), evaluatedDef, rewritingContext.global(), false);

        List<org.kframework.backend.java.kil.Rule> specRules = specRulesKORE.stream()
                .map(r -> converter.convert(Optional.<Module>empty(), r))
                .map(r -> new org.kframework.backend.java.kil.Rule(
                        r.label(),
                        r.leftHandSide().evaluate(rewritingContext), // TODO: drop?
                        r.rightHandSide().evaluate(rewritingContext), // TODO: drop?
                        r.requires(),
                        r.ensures(),
                        r.freshConstants(),
                        r.freshVariables(),
                        r.lookups(),
                        r,
                        rewritingContext.global())) // register definition to be used for execution of the current rule
                .collect(Collectors.toList());

        java.util.Collections.sort(specRules, new Comparator<org.kframework.backend.java.kil.Rule>() {
            @Override
            public int compare(org.kframework.backend.java.kil.Rule rule1, org.kframework.backend.java.kil.Rule rule2) {
                return Integer.compare(rule1.getLocation().startLine(), rule2.getLocation().startLine());
            }
        });

        // rename all variables again to avoid any potential conflicts with the rules in the semantics
        specRules = specRules.stream()
                .map(org.kframework.backend.java.kil.Rule::renameVariables)
                .collect(Collectors.toList());

        // rename all variables again to avoid any potential conflicts with the rules in the semantics
        List<org.kframework.backend.java.kil.Rule> targetSpecRules = specRules.stream()
                .map(org.kframework.backend.java.kil.Rule::renameVariables)
                .collect(Collectors.toList());

        //// prove spec rules
        SymbolicRewriter rewriter = new SymbolicRewriter(rewritingContextGlobal, kompileOptions.transition, new KRunState.Counter(), converter);

        assert (specRules.size() == targetSpecRules.size());

        List<ConstrainedTerm> startSyncNodes = new ArrayList<>();
        List<ConstrainedTerm> targetSyncNodes = new ArrayList<>();
        List<ConjunctiveFormula> startEnsures = new ArrayList<>();
        List<ConjunctiveFormula> targetEnsures = new ArrayList<>();
        List<Boolean> trusted = new ArrayList<>();

        for (int i = 0; i < specRules.size(); i++) {
            org.kframework.backend.java.kil.Rule startRule = specRules.get(i);
            org.kframework.backend.java.kil.Rule targetRule = targetSpecRules.get(i);

            // assert rule1.getEnsures().equals(rule2.getEnsures());

            // TODO: split requires for each side and for both sides in createLhsPattern
            startSyncNodes.add(startRule.createLhsPattern(rewritingContext));
            targetSyncNodes.add(targetRule.createLhsPattern(rewritingContext));
            startEnsures.add(startRule.getEnsures());
            targetEnsures.add(targetRule.getEnsures());

            // assert rule1.containsAttribute(Attribute.TRUSTED_KEY) == rule2.containsAttribute(Attribute.TRUSTED_KEY);
            trusted.add(startRule.containsAttribute(Attribute.TRUSTED_KEY));
        }

        return new Info(startSyncNodes, targetSyncNodes, startEnsures, targetEnsures, trusted, rewriter);
    }

    // TODO: better name
    public static GlobalContext getGlobal(CompiledDefinition compiledDef) {

        GlobalOptions globalOptions = new GlobalOptions();
        KompileOptions kompileOptions = new KompileOptions();
        KRunOptions krunOptions = new KRunOptions();
        JavaExecutionOptions javaExecutionOptions = new JavaExecutionOptions();

        KExceptionManager kem = new KExceptionManager(globalOptions);
        Stopwatch sw = new Stopwatch(globalOptions);
        FileUtil files = FileUtil.get(globalOptions, System.getenv());

        FileSystem fs = new PortableFileSystem(kem, files);
        Map<String, MethodHandle> hookProvider = HookProvider.get(kem); // new HashMap<>();
        InitializeRewriter.InitializeDefinition initializeDefinition = new InitializeRewriter.InitializeDefinition();

        //// setting options

        SMTOptions smtOptions = krunOptions.experimental.smt;

        //// creating rewritingContext

        GlobalContext initializingContextGlobal = new GlobalContext(fs, false, globalOptions, krunOptions, kem, smtOptions, hookProvider, files, Stage.INITIALIZING);
        TermContext initializingContext = TermContext.builder(initializingContextGlobal).freshCounter(0).build();
        org.kframework.backend.java.kil.Definition evaluatedDef = initializeDefinition.invoke(compiledDef.executionModule(), kem, initializingContext.global());

        GlobalContext rewritingContextGlobal = new GlobalContext(fs, false, globalOptions, krunOptions, kem, smtOptions, hookProvider, files, Stage.REWRITING);
        rewritingContextGlobal.setDefinition(evaluatedDef);
        TermContext rewritingContext = TermContext.builder(rewritingContextGlobal).freshCounter(initializingContext.getCounterValue()).build();

        return rewritingContextGlobal;
    }


    /**
     * compiledDef0: for parsing spec rules
     * compiledDef1: for symbolic execution
     */
    public void kprove(String proofFile, String prelude, CompiledDefinition compiledDef) {

        kapiGlobal.setSmtPrelude(prelude);

        // initialize rewriter
        Tuple2<SymbolicRewriter,TermContext> tuple = getRewriter(compiledDef);
        SymbolicRewriter rewriter = tuple._1();
        TermContext rewritingContext = tuple._2();
        KOREtoBackendKIL converter = rewriter.getConstructor();


        //// parse spec file

        Kompile kompile = new Kompile(kapiGlobal);
        Module specModule = kompile.parseModule(compiledDef, kapiGlobal.files.resolveWorkingDirectory(proofFile).getAbsoluteFile());

        scala.collection.Set<Module> alsoIncluded = Stream.of("K-TERM", "K-REFLECTION", RuleGrammarGenerator.ID_PROGRAM_PARSING)
                .map(mod -> compiledDef.getParsedDefinition().getModule(mod).get())
                .collect(org.kframework.Collections.toSet());

        specModule = new JavaBackend(kapiGlobal)
                .stepsForProverRules()
                .apply(Definition.apply(specModule, org.kframework.Collections.add(specModule, alsoIncluded), Att.apply()))
                .getModule(specModule.name()).get();

        ExpandMacros macroExpander = new ExpandMacros(compiledDef.executionModule(), kapiGlobal.kem, kapiGlobal.files, kapiGlobal.globalOptions, kapiGlobal.kompileOptions.transition, kapiGlobal.kompileOptions.experimental.smt);

        List<Rule> specRules = stream(specModule.localRules())
                .filter(r -> r.toString().contains("spec.k"))
                .map(r -> (Rule) macroExpander.expand(r))
                .map(r -> ProofExecutionMode.transformFunction(JavaBackend::ADTKVariableToSortedVariable, r))
                .map(r -> ProofExecutionMode.transformFunction(Kompile::convertKSeqToKApply, r))
                .map(r -> ProofExecutionMode.transform(NormalizeKSeq.self(), r))
                        //.map(r -> kompile.compileRule(compiledDefinition, r))
                .collect(Collectors.toList());

        //// massage spec rules

        List<org.kframework.backend.java.kil.Rule> javaRules = specRules.stream()
                .map(r -> converter.convert(Optional.<Module>empty(), r))
                .map(r -> new org.kframework.backend.java.kil.Rule(
                        r.label(),
                        r.leftHandSide().evaluate(rewritingContext),
                        r.rightHandSide().evaluate(rewritingContext),
                        r.requires(),
                        r.ensures(),
                        r.freshConstants(),
                        r.freshVariables(),
                        r.lookups(),
                        r,
                        rewritingContext.global()))
                .collect(Collectors.toList());
        List<org.kframework.backend.java.kil.Rule> allRules = javaRules.stream()
                .map(org.kframework.backend.java.kil.Rule::renameVariables)
                .collect(Collectors.toList());

        // rename all variables again to avoid any potential conflicts with the rules in the semantics
        javaRules = javaRules.stream()
                .map(org.kframework.backend.java.kil.Rule::renameVariables)
                .collect(Collectors.toList());

        //// prove spec rules

        List<ConstrainedTerm> proofResults = javaRules.stream()
                .filter(r -> !r.containsAttribute(Attribute.TRUSTED_KEY))
                .map(r -> rewriter.proveRule(r.createLhsPattern(rewritingContext,1), r.createRhsPattern(1), allRules))
                .flatMap(List::stream)
                .collect(Collectors.toList());

        //// print result

        //System.out.println(proofResults);

        List<K> result = proofResults.stream()
                .map(ConstrainedTerm::term)
                .map(t -> (KItem) t)
                .collect(Collectors.toList());

        System.out.println(result);
        return;
    }

}
