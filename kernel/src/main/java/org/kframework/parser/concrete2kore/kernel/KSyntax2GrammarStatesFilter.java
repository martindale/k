// Copyright (c) 2014-2015 K Team. All Rights Reserved.
package org.kframework.parser.concrete2kore.kernel;

import org.kframework.kil.loader.Constants;
import org.kframework.kore.Sort;
import org.kframework.definition.Module;
import org.kframework.definition.Production;
import org.kframework.definition.ProductionItem;
import org.kframework.definition.RegexTerminal;
import org.kframework.definition.Terminal;
import org.kframework.parser.concrete2kore.kernel.Grammar.NextableState;
import org.kframework.parser.concrete2kore.kernel.Grammar.NonTerminal;
import org.kframework.parser.concrete2kore.kernel.Grammar.NonTerminalState;
import org.kframework.parser.concrete2kore.kernel.Grammar.PrimitiveState;
import org.kframework.parser.concrete2kore.kernel.Grammar.RegExState;
import org.kframework.parser.concrete2kore.kernel.Grammar.RuleState;
import org.kframework.parser.concrete2kore.kernel.Rule.AddLocationRule;
import org.kframework.parser.concrete2kore.kernel.Rule.DeleteRule;
import org.kframework.parser.concrete2kore.kernel.Rule.WrapLabelRule;
import org.kframework.utils.errorsystem.KExceptionManager;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import static org.kframework.Collections.iterable;
import static org.kframework.Collections.stream;

/**
 * A simple visitor that goes through every accessible production and creates the NFA states for the
 * parser. First step is to create a NonTerminal object for every declared syntactic sort. These
 * will be referenced each time a NonTerminalState is created.
 */
public class KSyntax2GrammarStatesFilter {

    public static Grammar getGrammar(Module module) {
        Grammar grammar = new Grammar();
        Set<String> rejects = new HashSet<>();
        // create a NonTerminal for every declared sort
        for (Sort sort : iterable(module.definedSorts())) {
            grammar.add(new NonTerminal(sort.name()));
        }

        stream(module.productions()).forEach(p -> collectTokens(p, rejects));
        stream(module.productions()).forEach(p -> processProduction(p, grammar, rejects));

        grammar.addWhiteSpace();
        grammar.compile();
        return grammar;
    }

    private static void collectTokens(Production prd, Set<String> rejects) {
        for (ProductionItem prdItem : iterable(prd.items())) {
            String pattern = "";
            if (prdItem instanceof Terminal) {
                if (!((Terminal) prdItem).value().equals("")) {
                    pattern = ((Terminal) prdItem).value();
                    rejects.add(pattern);
                }
            }
        }
    }

    public static void processProduction(Production prd, Grammar grammar, Set<String> rejects) {
        if(prd.att().contains("notInPrograms") || prd.att().contains("reject"))
            return;

        NonTerminal nt = grammar.get(prd.sort().name());
        assert nt != null : "Could not find in the grammar the required sort: " + prd.sort();
        NextableState previous = nt.entryState;
        // all types of production follow pretty much the same pattern
        // previous = entryState
        // loop: add a new State to the 'previous' state; update 'previous' state

        // just a normal production with Terminals and Sort alternations
        // this will create a labeled KApp with the same arity as the
        // production
        for (ProductionItem prdItem : iterable(prd.items())) {
            if (prdItem instanceof Terminal) {
                Terminal terminal = (Terminal) prdItem;
                PrimitiveState pstate = new RegExState(prd.sort() + "-T", nt,
                        Pattern.compile(terminal.value(), Pattern.LITERAL), prd);
                previous.next.add(pstate);
                RuleState del = new RuleState("DelTerminalRS", nt, new DeleteRule(1, true));
                pstate.next.add(del);
                previous = del;
            } else if (prdItem instanceof org.kframework.definition.NonTerminal) {
                org.kframework.definition.NonTerminal srt = (org.kframework.definition.NonTerminal) prdItem;
                NonTerminalState nts = new NonTerminalState(prd.sort() + "-S", nt,
                        grammar.get(srt.sort().name()), false);
                previous.next.add(nts);
                previous = nts;
            } else if (prdItem instanceof RegexTerminal) {
                RegexTerminal lx = (RegexTerminal) prdItem;
                Pattern p;
                try {
                    p = Pattern.compile(lx.regex());
                } catch (PatternSyntaxException ex) {
                    p = Pattern.compile("NoMatch");
                    String msg = "Lexical pattern not compatible with the new parser.";
                    throw KExceptionManager.compilerError(msg, ex); // TODO: add location
                }
                PrimitiveState pstate = new RegExState(prd.sort().name() + "-T", nt, p, prd);
                RuleState del = new RuleState("DelRegexTerminalRS", nt, new DeleteRule(1, true));
                previous.next.add(pstate);
                pstate.next.add(del);
                previous = del;
            } else {
                assert false : "Didn't expect this ProductionItem type: "
                        + prdItem.getClass().getName();
            }
        }
        Pattern pattern = null;
        Set<String> rejectTokens = new HashSet<>();
        if (prd.att().contains("token")) {
            // if there is only one regular expression, optimize rejects by testing which would be able to match
            if (prd.att().contains(Constants.AUTOREJECT)) {
                rejectTokens = rejects;
                if (prd.items().size() == 1 && prd.items().head() instanceof RegexTerminal) {
                    Pattern token = Pattern.compile(((RegexTerminal) prd.items().head()).regex());
                    rejectTokens = rejects.stream().filter(s -> token.matcher(s).matches()).collect(Collectors.toSet());
                }
            }
            if (prd.att().contains(Constants.REJECT2))
                pattern = Pattern.compile(prd.att().get(Constants.REJECT2).get().toString());
        }
        RuleState labelRule = new RuleState("AddLabelRS", nt, new WrapLabelRule(prd, pattern, rejectTokens));
        previous.next.add(labelRule);
        previous = labelRule;

        RuleState locRule = new RuleState(prd.sort() + "-R", nt, new AddLocationRule());
        previous.next.add(locRule);
        previous = locRule;

        previous.next.add(nt.exitState);
    }
}