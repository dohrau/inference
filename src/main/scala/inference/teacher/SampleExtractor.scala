/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.teacher

import com.typesafe.scalalogging.Logger
import inference.core._
import inference.input.{Configuration, Input}
import inference.teacher.state.{Adaptor, ModelEvaluator, PermissionEvaluator, Snapshot, StateEvaluator}
import inference.util.ast.{InferenceInfo, InstanceInfo, LocationInfo}
import viper.silicon.interfaces.SiliconRawCounterexample
import viper.silver.ast
import viper.silver.verifier.VerificationError
import viper.silver.verifier.reasons.InsufficientPermission

/**
 * A sample extractor mixin.
 */
trait SampleExtractor {
  /**
   * Type shorthand for counter examples.
   */
  private type Counter = SiliconRawCounterexample

  /**
   * Returns the logger.
   *
   * @return The logger.
   */
  protected def logger: Logger

  /**
   * Returns the input to the inference.
   *
   * @return The input.
   */
  protected def input: Input

  /**
   * Returns the configuration.
   *
   * @return The configuration.
   */
  private def configuration: Configuration =
    input.configuration

  /**
   * Extracts a sample from the given framing query and verification error.
   *
   * @param query The framing query that caused the error.
   * @param error The verification error.
   * @return The extracted sample.
   */
  protected def extractFramingSample(query: Query, error: VerificationError): Sample = {
    // extract counter-example and offending location
    val (counter, offending, info) = extractInformation(error)

    val location = info match {
      case Some(LocationInfo(location)) => location
      case other => sys.error(s"Location info expected but found $other.")
    }

    // get label and instance
    val (label, instance) = {
      val heaps = counter.state.oldHeaps
      query
        .snapshots
        .filter { case (label, _) => heaps.contains(label) }
        .head
    }

    // compute state abstraction
    val state = {
      val model = ModelEvaluator(counter.model)
      val state = StateEvaluator(Some(label), counter.state, model)
      val snapshot = Snapshot(instance, state)
      StateAbstraction(snapshot)
    }

    // create sample
    val sample = {
      // get specification placeholder
      val placeholder = instance.placeholder
      // create left-hand side of implication
      // TODO: Do we need to take into account reachability?
      val left = {
        val resource = SetAbstraction(Set(location))
        ExhaledRecord(placeholder, state, resource, 0)
      }
      // create right-hand side of implication
      val right = {
        val resource = SetAbstraction(Set(offending))
        val record = InhaledRecord(placeholder, state, resource, 0)
        LowerBound(Seq(record))
      }
      // create implication sample
      Implication(left, right)
    }

    // return sample
    logger.info(sample.toString)
    sample
  }

  /**
   * Extracts a sample from the given basic query and verification error.
   *
   * @param query The basic query that caused the error.
   * @param error The verification error.
   * @return The extracted sample.
   */
  protected def extractBasicSample(query: Query, error: VerificationError): Sample = {
    // extract counter-example and offending location
    val (counter, offending, info) = extractInformation(error)

    // get silicon state and model
    val siliconState = counter.state
    val model = ModelEvaluator(counter.model)

    // get state snapshots
    val (failingSnapshot, otherSnapshots) = {
      // gather all encountered snapshots
      val snapshots = query
        .snapshots
        .flatMap {
          case (name, instance) if siliconState.oldHeaps.contains(name) =>
            val state = StateEvaluator(Some(name), siliconState, model)
            val snapshot = Snapshot(instance, state)
            Some(snapshot)
          case _ => None
        }
      // return current and other snapshots
      if (info.isDefined) {
        val current = snapshots.lastOption
        val others = snapshots.init
        (current, others)
      } else {
        val current = None
        (current, snapshots)
      }
    }

    // failing state
    val failState = failingSnapshot match {
      case Some(snapshot) => snapshot.state
      case None => StateEvaluator(None, siliconState, model)
    }

    /**
     * Helper method that computes a record corresponding to the given snapshot.
     *
     * @param snapshot The snapshot.
     * @return The record.
     */
    def recordify(snapshot: Snapshot): Record = {
      // adapt locations
      val locations = {
        val adaptor = Adaptor(failState, snapshot)
        adaptor.adaptLocation(offending)
      }
      // permission difference
      val amount = locations
        .headOption
        .map { formal =>
          val state = snapshot.state
          val actual = snapshot.instance.instantiate(formal)
          val instance = snapshot.instance
          val hypothesis = query.hypothesis
          evaluatePermission(actual, instance, hypothesis, state)
        }
        .getOrElse(0)
      // get placeholder and create abstractions
      val placeholder = snapshot.placeholder
      val state = StateAbstraction(snapshot)
      val resource = {
        val actual = SetAbstraction(locations)
        val dummy = offending match {
          case ast.FieldAccess(receiver, field) =>
            val reference = failState.evaluateReference(receiver)
            FieldAbstraction(reference, field)
          case ast.PredicateAccess(arguments, name) =>
            val references = arguments.map(failState.evaluateReference)
            PredicateAbstraction(name, references)
        }
        DebugAbstraction(actual, dummy)
      }
      // create record
      if (query.isExhaled(snapshot.label)) {
        ExhaledRecord(placeholder, state, resource, amount)
      } else {
        InhaledRecord(placeholder, state, resource, amount)
      }
    }

    // create sample
    val sample = failingSnapshot match {
      // if there is a failing snapshot the error was caused by some specification
      case Some(snapshot) =>
        // compute record corresponding to failing specification
        val failing = recordify(snapshot)
        // if the failing specification exhales more than one permission we want to impose an upper bound, otherwise we
        // want to require the missing permission from an upstream specification
        if (failing.delta < -1) {
          // create upper bound sample
          UpperBound(failing)
        } else {
          // create implication sample
          val others = otherSnapshots.map(recordify)
          Implication(failing, LowerBound(others))
        }
      // if there is no failing snapshot the error was caused by some original program code
      case None =>
        val others = otherSnapshots.map(recordify)
        LowerBound(others)
    }

    // return sample
    logger.info(sample.toString)
    sample
  }

  /**
   * Extracts information form the given verification error. The information consists of a counterexample, an offending
   * location, and an optionally attached info.
   *
   * @param error The verification error.
   * @return The extracted information.
   */
  private def extractInformation(error: VerificationError): (Counter, ast.LocationAccess, Option[InferenceInfo[Any]]) = {
    // extract counterexample
    val counter = error.counterexample match {
      case Some(value: Counter) => value
      case Some(_) => sys.error("Unsupported counterexample.")
      case _ => sys.error("No counterexample.")
    }
    // extract offending location
    val offending = error.reason match {
      case InsufficientPermission(location) => location
      case reason => sys.error(s"Unexpected reason: $reason")
    }
    // extract attached info value
    val info = error.offendingNode match {
      case node: ast.Infoed =>
        node.info match {
          case info: InferenceInfo[Any] => Some(info)
          case _ => None
        }
      case _ => None
    }
    // instantiate offending location
    val instantiated = info match {
      case Some(InstanceInfo(instance)) =>
        if (configuration.noInlining() || instance.placeholder.isRecursive) instance.instantiate(offending)
        else offending
      case _ =>
        offending
    }
    // return information
    (counter, instantiated, info)
  }

  /**
   * Evaluates the permission amount for the given resource represented by the specification corresponding to the given
   * specification instance under consideration of the given hypothesis and state.
   *
   * @param resource   The resource.
   * @param instance   The specification instance.
   * @param hypothesis The hypothesis.
   * @param state      The state.
   * @return The permission amount.
   */
  def evaluatePermission(resource: ast.Exp, instance: Instance, hypothesis: Hypothesis, state: StateEvaluator): Int = {
    val evaluator = new PermissionEvaluator(input, hypothesis, state)
    evaluator.evaluate(resource, instance, depth = 2)
  }
}
