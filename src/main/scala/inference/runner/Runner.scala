/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.runner

import inference.core.{AbstractLearner, AbstractTeacher, Hypothesis, Inference}
import inference.learner.Learner
import inference.teacher.Teacher
import inference.util.solver.{Solver, Z3Solver}
import viper.silicon.Silicon
import viper.silver.verifier.Verifier

/**
 * An inference runner.
 *
 * @tparam R The result type.
 */
trait Runner[R] extends Inference {
  /**
   * Creates a verifier with the given configuration.
   *
   * @param configuration The configuration.
   * @return The verifier.
   */
  def createVerifier(configuration: Configuration): Verifier = {
    // create instance
    val instance = new Silicon()
    // pass arguments
    val arguments = Seq(
      "--z3Exe", configuration.z3Exe(),
      "--counterexample", "raw",
      "--enableMoreCompleteExhale",
      "--ignoreFile", "dummy.vpr")
    instance.parseCommandLine(arguments)
    // return instance
    instance
  }

  /**
   * Creates a solver with the given configuration.
   *
   * @param configuration The configuration.
   * @return The solver.
   */
  def createSolver(configuration: Configuration): Solver =
    new Z3Solver(configuration.z3Exe())

  override protected def createTeacher(input: Input, verifier: Verifier): AbstractTeacher =
    new Teacher(input, verifier)

  override protected def createLearner(input: Input, solver: Solver): AbstractLearner =
    new Learner(input, solver)

  /**
   * Runs the inference with the given arguments.
   *
   * @param arguments The arguments.
   * @return The result.
   */
  def run(arguments: Seq[String]): R = {
    val configuration = new Configuration(arguments)
    run(configuration)
  }

  /**
   * Runs the inference with the given configuration.
   *
   * @param configuration The configuration.
   * @return The result.
   */
  def run(configuration: Configuration): R = {
    // create input
    val input = Input(configuration)
    // create verifier and solver
    val verifier = createVerifier(configuration)
    val solver = createSolver(configuration)
    // before
    verifier.start()
    // run
    val result = run(input)(verifier, solver)
    // after
    verifier.stop()
    // return result
    result
  }

  /**
   * Runs the inference with the given input.
   *
   * @param input The input.
   * @return The result.
   */
  def run(input: Input)(verifier: Verifier, solver: Solver): R = {
    val hypothesis = infer(input)(verifier, solver)
    result(input, hypothesis)
  }

  /**
   * Computes the result from the given input and inferred hypothesis.
   *
   * @param input      The input.
   * @param hypothesis The inferred hypothesis.
   * @return The result.
   */
  def result(input: Input, hypothesis: Hypothesis): R
}

/**
 * An inference runner that prints the inferred hypothesis.
 */
trait PrintRunner extends Runner[Unit] {
  override def result(input: Input, hypothesis: Hypothesis): Unit =
    println(hypothesis)
}
