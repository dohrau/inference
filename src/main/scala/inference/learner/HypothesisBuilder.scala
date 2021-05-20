/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.learner

import inference.Names
import inference.core.Hypothesis
import inference.util.ast.Expressions
import viper.silver.ast

/**
 * A hypothesis builder.
 */
trait HypothesisBuilder {
  /**
   * Builds a hypothesis corresponding to the given templates under the given model.
   *
   * @param templates The templates.
   * @param model     The model.
   * @return The hypothesis.
   */
  def buildHypothesis(templates: Seq[Template], model: Map[String, Boolean]): Hypothesis = {
    // build predicates
    val predicates = templates.map {
      case template: PredicateTemplate => buildPredicate(template, model)
    }
    // create hypothesis
    Hypothesis(predicates)
  }

  /**
   * Builds a predicate corresponding to the given template model.
   *
   * @param template The predicate template.
   * @param model    The model.
   * @return The predicate.
   */
  private def buildPredicate(template: PredicateTemplate, model: Map[String, Boolean]): ast.Predicate = {
    // get placeholder and extract name, parameters, and atoms
    val placeholder = template.placeholder
    val name = placeholder.name
    val parameters = placeholder.parameters
    val atoms = placeholder.atoms
    // build body
    val body = buildExpression(template.body, atoms, model)
    // create predicate
    ast.Predicate(name, parameters, Some(body))()
  }

  /**
   * Builds an expression to the given expression corresponding to the given model.
   *
   * @param expression The template expression.
   * @param atoms      The atoms upon which the expression may depend.
   * @param model      The model.
   * @return The expression.
   */
  private def buildExpression(expression: TemplateExpression, atoms: Seq[ast.Exp], model: Map[String, Boolean]): ast.Exp =
    expression match {
      case Wrapped(expression) =>
        expression
      case Conjunction(conjuncts) =>
        val mapped = conjuncts.map { conjunct => buildExpression(conjunct, atoms, model) }
        Expressions.conjoin(mapped)
      case Guarded(guardId, body) =>
        val guard = buildGuard(guardId, atoms, model)
        val guarded = buildExpression(body, atoms, model)
        ast.Implies(guard, guarded)()
    }

  /**
   * Builds the guard with the given id corresponding to the given model.
   *
   * @param guardId The guard id.
   * @param atoms   The atoms upon which the guard may depend.
   * @param model   The model.
   * @return The guard.
   */
  private def buildGuard(guardId: Int, atoms: Seq[ast.Exp], model: Map[String, Boolean]): ast.Exp = {
    val maxClauses = 1
    // build clauses
    val clauses = for (clauseIndex <- 0 until maxClauses) yield {
      val clauseActivation = model.getOrElse(Names.clauseActivation(guardId, clauseIndex), false)
      if (clauseActivation) {
        val literals = atoms
          .zipWithIndex
          .map { case (atom, literalIndex) => model
            .get(Names.literalActivation(guardId, clauseIndex, literalIndex))
            .flatMap { literalActivation =>
              if (literalActivation) model
                .get(Names.literalSign(guardId, clauseIndex, literalIndex))
                .map { sign => if (sign) atom else ast.Not(atom)() }
              else None
            }
            .getOrElse(ast.TrueLit()())
          }
        // conjoin literals
        Expressions.conjoin(literals)
      } else ast.FalseLit()()
    }
    // disjoin clauses
    Expressions.disjoin(clauses)
  }
}