/**
 * Quest lifecycle, objective predicates and one-time reward settlement as resolved, checked and
 * written by canonical commits (P10.3). {@code QuestRules} is the prose-free projection of a pinned
 * pack's quests, {@code QuestPredicate} evaluates their bounded world predicates over committed
 * facts, {@code QuestPlan} is the pure, deterministic transition engine a resolver runs after every
 * fact-changing command, {@code QuestChanges} is the immutable result a commit receives and
 * {@code QuestWriter} applies it inside the commit's transaction with stale-state guards. Nothing here
 * reads natural language: a model can suggest that an objective looks satisfied, but only a predicate
 * over committed state moves a quest.
 */
package org.rpgengine.quests;
