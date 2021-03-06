// Copyright (c) 2015-2016 K Team. All Rights Reserved.
package org.kframework.frontend.compile;

import org.kframework.attributes.Att;
import org.kframework.builtin.BooleanUtils;
import org.kframework.definition.Context;
import org.kframework.definition.Rule;
import org.kframework.definition.Sentence;
import org.kframework.definition.SentenceBasedModuleTransformer;
import org.kframework.frontend.KORE;
import org.kframework.kil.Sort;
import org.kframework.frontend.K;
import org.kframework.frontend.KApply;

import java.util.Set;

import static org.kframework.definition.Constructors.*;
import static org.kframework.frontend.KORE.*;

public class ResolveHeatCoolAttribute extends SentenceBasedModuleTransformer {

    private Set<String> transitions;

    public ResolveHeatCoolAttribute(Set<String> transitions) {
        this.transitions = transitions;
    }

    private Rule resolve(Rule rule) {
        return Rule(
                rule.body(),
                transform(rule.requires(), rule.att()),
                rule.ensures(),
                rule.att());
    }

    private Context resolve(Context context) {
        return new Context(
                context.body(),
                transform(context.requires(), context.att()),
                context.att());
    }

    private K transform(K requires, Att att) {
        String sort = att.<String>getOptional("result").orElse(Sort.KRESULT.getName());
        KApply predicate = KORE.KApply(KLabel("is" + sort), KVariable("HOLE"));
        if (att.contains("heat")) {
            return BooleanUtils.and(requires, BooleanUtils.not(predicate));
        } else if (att.contains("cool")) {
            if (transitions.stream().anyMatch(att::contains)) {
                // if the cooling rule is a super strict, then tag the isKResult predicate and drop it during search
                predicate = KORE.KApply(predicate.klabel(), predicate.klist(), predicate.att().add(Att.transition()));
            }
            return BooleanUtils.and(requires, predicate);
        }
        throw new AssertionError("unreachable");
    }

    public Sentence process(Sentence s) {
        if (!s.att().contains("heat") && !s.att().contains("cool")) {
            return s;
        }
        if (s instanceof Rule) {
            return resolve((Rule) s);
        } else if (s instanceof Context) {
            return resolve((Context) s);
        } else {
            return s;
        }
    }
}
