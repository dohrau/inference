/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.learner

import inference.core.{AbstractLearner, Hypothesis}
import inference.input.Input
import inference.util.solver.Solver

import scala.annotation.tailrec

/**
 * The default implementation of the learner.
 *
 * @param input  The input to the inference.
 * @param solver The solver used to generate hypotheses.
 */
class Learner(val input: Input, protected val solver: Solver)
  extends AbstractLearner
    with TemplateGenerator
    with HypothesisSolver
    with HypothesisBuilder {
  /**
   * The set used to remember all hypotheses.
   */
  private var history: Set[Hypothesis] =
    Set.empty

  override def initial: Hypothesis =
    Hypothesis(Seq.empty, Seq.empty)

  override def hypothesis: Option[Hypothesis] = {
    if (samples.isEmpty) {
      // return initial hypothesis
      Some(initial)
    } else {
      // reset escalation level
      if (configuration.deescalation) {
        deescalate()
      }
      // generate templates
      val templates = generateTemplates()
      // compute hypothesis and make sure it is a new one
      val next = hypothesis(templates)
      next.flatMap { hypothesis =>
        if (history.contains(hypothesis)) {
          logger.info("Duplicate hypothesis!")
          None
        } else {
          history = history + hypothesis
          Some(hypothesis)
        }
      }
    }
  }

  /**
   * Computes a hypothesis based on the given templates.
   *
   * @param templates The templates.
   * @return The hypothesis
   */
  @tailrec
  private def hypothesis(templates: Seq[Template]): Option[Hypothesis] = {
    // solve constraints
    val model = solve(templates)
    // build model or escalate
    model match {
      case Some(model) =>
        val result = buildHypothesis(templates, model)
        Some(result)
      case None =>
        if (canEscalate) {
          escalate()
          hypothesis(templates)
        } else None
    }
  }
}
