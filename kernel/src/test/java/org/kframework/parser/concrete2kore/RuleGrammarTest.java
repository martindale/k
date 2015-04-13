// Copyright (c) 2015 K Team. All Rights Reserved.
package org.kframework.parser.concrete2kore;

import com.beust.jcommander.internal.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kframework.definition.Definition;
import org.kframework.definition.Module;
import org.kframework.kil.Sources;
import org.kframework.main.GlobalOptions;
import org.kframework.main.GlobalOptions.Warnings;
import org.kframework.parser.Term;
import org.kframework.parser.concrete2kore.generator.RuleGrammarGenerator;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.errorsystem.ParseFailedException;
import org.kframework.utils.file.JarInfo;
import scala.Tuple2;
import scala.util.Either;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import static org.kframework.Collections.*;
import static org.kframework.definition.Constructors.*;

public class RuleGrammarTest {
    private File definitionFile = null;
    private final static String startSymbol = "RuleContent";
    private RuleGrammarGenerator gen;
    public static final File BUILTIN_DIRECTORY = JarInfo.getKIncludeDir().resolve("builtin").toFile();

    @Before
    public void setUp() throws  Exception{
        definitionFile = new File(BUILTIN_DIRECTORY.toString() + "/kast.k");

        String definitionText;
        try {
            definitionText = FileUtils.readFileToString(definitionFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        java.util.Set<Module> modules =
                ParserUtils.loadModules(definitionText,
                        Sources.fromFile(definitionFile),
                        definitionFile.getParentFile(),
                        Lists.newArrayList(BUILTIN_DIRECTORY));

        gen = new RuleGrammarGenerator(modules);
    }

    private void parseRule(String input, String def, int warnings, boolean expectedError) {
        Module test = ParserUtils.parseMainModuleOuterSyntax(def, "TEST");
        ParseInModule parser = gen.getRuleGrammar(test);
        Tuple2<Either<Set<ParseFailedException>, Term>, Set<ParseFailedException>> rule = parser.parseString(input, startSymbol);;
        printout(rule, warnings, expectedError);
    }

    private void parseConfig(String input, String def, int warnings, boolean expectedError) {
        Module test = ParserUtils.parseMainModuleOuterSyntax(def, "TEST");
        ParseInModule parser = gen.getConfigGrammar(test);
        Tuple2<Either<Set<ParseFailedException>, Term>, Set<ParseFailedException>> rule = parser.parseString(input, startSymbol);;
        printout(rule, warnings, expectedError);
    }

    private void printout(Tuple2<Either<Set<ParseFailedException>, Term>, Set<ParseFailedException>> rule, int warnings, boolean expectedError) {
        if (false) { // true to print detailed results
            KExceptionManager kem = new KExceptionManager(new GlobalOptions(true, Warnings.ALL, true));
            if (rule._1().isLeft()) {
                for (ParseFailedException x : rule._1().left().get()) {
                    kem.addKException(x.getKException());
                }
            } else {
                System.err.println("rule = " + rule._1().right().get());
            }
            for (ParseFailedException x : rule._2()) {
                kem.addKException(x.getKException());
            }
            kem.print();
        }
        Assert.assertEquals("Expected " + warnings + " warnings: ", warnings, rule._2().size());
        if (expectedError)
            Assert.assertTrue("Expected error here: ", rule._1().isLeft());
        else
            Assert.assertTrue("Expected no errors here: ", rule._1().isRight());
    }

    @Test
    public void test1() {
        Assert.assertNotNull(definitionFile);
    }

    // test proper associativity for rewrite, ~> and cast
    @Test
    public void test2() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus), left] " +
                "| r\"[0-9]+\" [token] " +
                "syntax left 'Plus " +
                "endmodule";
        parseRule("1+2=>A:Exp~>B:>Exp", def, 1, false);
    }

    // test variable disambiguation when a variable is declared by the user
    @Test
    public void test3() {
        String def = "" +
                "module TEST " +
                "syntax Exps ::= Exp \",\" Exps [klabel('Exps)] " +
                "| Exp " +
                "syntax Exp ::= Id " +
                "syntax Stmt ::= \"val\" Exps \";\" Stmt [klabel('Decl)] " +
                "syntax KBott ::= \"(\" K \")\" [bracket, klabel('bracket)] " +
                "| (Id, Stmt) [klabel('tuple)] " +
                "syntax Id " +
                "syntax K " +
                "endmodule";
        parseRule("val X ; S:Stmt => (X, S)", def, 1, false);
    }

    // test variable disambiguation when all variables are being inferred
    @Test
    public void test4() {
        String def = "" +
                "module TEST " +
                "syntax Exps ::= Exp \",\" Exps [klabel('Exps)] " +
                "| Exp " +
                "syntax Exp ::= Id " +
                "syntax Stmt ::= \"val\" Exps \";\" Stmt [klabel('Decl)] " +
                "syntax KBott ::= \"(\" K \")\" [bracket, klabel('bracket)] " +
                "| (Id, Stmt) [klabel('tuple)] " +
                "syntax Id " +
                "syntax K " +
                "endmodule";
        parseRule("val X ; S => (X, S)", def, 2, false);
    }

    // test error reporting when + is non-associative
    @Test
    public void test5() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus), non-assoc] " +
                "| r\"[0-9]+\" [token] " +
                "syntax non-assoc 'Plus " +
                "endmodule";
        parseRule("1+2+3", def, 0, true);
    }

    // test AmbFilter which should report ambiguities and return a clean term
    @Test
    public void test6() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus)] " +
                "| r\"[0-9]+\" [token] " +
                "endmodule";
        parseRule("1+2+3", def, 1, false);
    }

    // test error reporting when rewrite priority is not met
    @Test
    public void test7() {
        String def = "" +
                "module TEST " +
                "syntax A ::= \"foo\" A [klabel('foo)] " +
                "syntax B ::= \"bar\"   [klabel('bar)] " +
                "endmodule";
        parseRule("foo bar => X", def, 0, true);
    }

    // test prefer and avoid
    @Test
    public void test8() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus), prefer] " +
                "| Exp \"*\" Exp [klabel('Mul)] " +
                "| r\"[0-9]+\" [token] " +
                "endmodule";
        parseRule("1+2*3", def, 0, false);
    }

    // test cells
    @Test
    public void test9() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus), prefer] " +
                "| Exp \"*\" Exp [klabel('Mul)] " +
                "| r\"[0-9]+\" [token] " +
                "syntax K " +
                "syntax TopCell ::= \"<T>\" KCell StateCell \"</T>\" [klabel(<T>), cell] " +
                "syntax KCell ::= \"<k>\" K \"</k>\" [klabel(<k>), cell] " +
                "syntax StateCell ::= \"<state>\" K \"</state>\" [klabel(<state>), cell] " +
                "endmodule";
        parseRule("<T> <k>...1+2*3...</k> `<state> A => .::K ...</state> => .::Bag` ...</T>", def, 1, false);
    }

    // test rule cells
    @Test
    public void test10() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \",\" Exp [klabel('Plus), prefer] " +
                "| Exp \"*\" Exp [klabel('Mul)] " +
                "| r\"[0-9]+\" [token] " +
                "endmodule";
        parseRule("A::KLabel(B::K, C::K, D::K)", def, 0, false);
    }

    // test config cells
    @Test
    public void test11() {
        String def = "" +
                "module TEST " +
                "syntax Exp ::= Exp \"+\" Exp [klabel('Plus), prefer] " +
                "| Exp \"*\" Exp [klabel('Mul)] " +
                "| r\"[0-9]+\" [token] " +
                "syntax K " +
                "endmodule";
        parseConfig("<T multiplicity=\"*\"> <k> 1+2*3 </k> `<state> A => .::K </state> => .::Bag` </T>", def, 1, false);
    }

    // test variable disambiguation when all variables are being inferred
    @Test
    public void test12() {
        String def = "" +
                "module TEST " +
                "syntax Stmt ::= \"val\" Exp \";\" Stmt [klabel('Decl)] " +
                "syntax Exp " +
                "syntax Stmt " +
                "endmodule";
        parseRule("val _:Exp ; _", def, 0, false);
    }

    // test priority exceptions (requires and ensures)
    @Test
    public void test13() {
        String def = "" +
                "module TEST " +
                "endmodule";
        parseRule(".::K => .::K requires .::K", def, 0, false);
    }

    // test automatic follow restriction for terminals
    @Test
    public void test14() {
        String def = "" +
                "module TEST " +
                "syntax Stmt ::= Stmt Stmt [klabel('Stmt)] " +
                "syntax Exp ::= K \"==\" K [klabel('Eq)] " +
                "syntax Exp ::= K \":\" K [klabel('Colon)] " +
                "syntax K " +
                "syntax Exp ::= K \"==K\" K [klabel('EqK)] " +
                "endmodule";
        parseRule("A::K ==K A", def, 0, false);
        parseRule("A::K == K A", def, 0, true);
        parseRule("A:K", def, 0, false);
        parseRule("A: K", def, 2, false);
    }
}