// Copyright (c) 2014-2015 K Team. All Rights Reserved.
package org.kframework.parser.concrete2kore;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.kframework.kore.Sort;
import org.kframework.definition.Module;
import org.kframework.definition.Production;
import org.kframework.definition.ProductionItem;
import org.kframework.definition.RegexTerminal;
import org.kframework.definition.Terminal;
import org.kframework.parser.generator.CollectTerminalsVisitor;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.parser.concrete2kore.Rule.*;
import org.kframework.parser.concrete2kore.Grammar.*;

import static org.kframework.Collections.*;

/**
 * A simple visitor that goes through every accessible production and creates the NFA states for the
 * parser. First step is to create a NonTerminal object for every declared syntactic sort. These
 * will be referenced each time a NonTerminalState is created.
 */
public class KSyntax2GrammarStatesFilter {

    public static Grammar getGrammar(Module module) {
        Grammar grammar = new Grammar();
        // create a NonTerminal for every declared sort
        for (Sort sort : iterable(module.definedSorts())) {
            grammar.add(new NonTerminal(sort.name()));
        }

        stream(module.productions()).forEach(p -> processProduction(p, grammar));

        grammar.addWhiteSpace();
        grammar.compile();
        return grammar;
    }

    private CollectTerminalsVisitor ctv;

    public static void processProduction(Production prd, Grammar grammar) {
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
                        Pattern.compile(terminal.value(), Pattern.LITERAL), null);
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
                PrimitiveState pstate = new RegExState(prd.sort().name() + "-T", nt, p, prd); // TODO: replace kil.Production with Sort in Grammar and parser, then replace this null
                previous.next.add(pstate);
                previous = pstate;
            } else {
                assert false : "Didn't expect this ProductionItem type: "
                        + prdItem.getClass().getName();
            }
        }
        RuleState labelRule = new RuleState("AddLabelRS", nt, new WrapLabelRule(prd));
        previous.next.add(labelRule);
        previous = labelRule;

        RuleState locRule = new RuleState(prd.sort() + "-R", nt, new AddLocationRule());
        previous.next.add(locRule);
        previous = locRule;

        previous.next.add(nt.exitState);
    }
}
