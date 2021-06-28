/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.core

import inference.teacher.state.Snapshot
import viper.silver.ast

/**
 * A sample.
 */
sealed trait Sample {
  /**
   * Returns the records mentioned by the sample.
   *
   * @return The records.
   */
  def records: Seq[Record]
}

/**
 * A sample imposing a lower bound.
 *
 * @param records The records.
 * @param bound   The lower bound.
 */
case class LowerBound(records: Seq[Record], bound: Int) extends Sample

/**
 * A sample imposing an upper bound.
 *
 * @param record The record.
 * @param bound  The upper bound.
 */
case class UpperBound(record: Record, bound: Int) extends Sample {
  override def records: Seq[Record] =
    Seq(record)
}

/**
 * An implication sample.
 *
 * @param left  The left-hand side of the implication.
 * @param right The right-hand side of the implication.
 */
case class Implication(left: Record, right: LowerBound) extends Sample {
  override def records: Seq[Record] =
    left +: right.records
}

/**
 * A record representing a data point.
 */
sealed trait Record {
  /**
   * Returns the specification placeholder corresponding to this data point.
   *
   * @return The specification placeholder.
   */
  def placeholder: Placeholder

  /**
   * Returns the state abstraction.
   *
   * @return The state abstraction.
   */
  def abstraction: Abstraction

  /**
   * Returns the set of locations that can be used to represent the offending resource.
   *
   * @return The set of locations referring to the offending resource.
   */
  def locations: Set[ast.LocationAccess]
}

/**
 * A record representing a data point corresponding to an inhaled state snapshot.
 *
 * @param placeholder See [[Record.placeholder]].
 * @param abstraction See [[Record.abstraction]].
 * @param locations   See [[Record.locations]].
 */
case class InhaledRecord(placeholder: Placeholder, abstraction: Abstraction, locations: Set[ast.LocationAccess]) extends Record

/**
 * A record representing a data point corresponding to an exhaled state snapshot.
 *
 * @param placeholder See [[Record.placeholder]].
 * @param abstraction See [[Record.abstraction]].
 * @param locations   See [[Record.locations]].
 */
case class ExhaledRecord(placeholder: Placeholder, abstraction: Abstraction, locations: Set[ast.LocationAccess]) extends Record

/**
 * A state abstraction.
 */
trait Abstraction {
  /**
   * Evaluates the given atomic predicate in the abstract state.
   *
   * @param atom The atomic predicate to evaluate.
   * @return The predicate value.
   */
  def evaluate(atom: ast.Exp): Option[Boolean]

  /**
   * Evaluates the given atomic predicates in the abstract state.
   *
   * @param atoms The atomic predicates to evaluate.
   * @return The predicate values.
   */
  def evaluate(atoms: Seq[ast.Exp]): Seq[Option[Boolean]] =
    atoms.map(evaluate)
}

/**
 * A state abstracted by some snapshot.
 *
 * @param snapshot The snapshot.
 */
case class SnapshotAbstraction(snapshot: Snapshot) extends Abstraction {
  override def evaluate(atom: ast.Exp): Option[Boolean] = {
    // TODO: Can the value ever be unknown?
    val actual = snapshot.instance.instantiate(atom)
    val value = snapshot.state.evaluateBoolean(actual)
    Some(value)
  }

  override def toString: String =
    snapshot
      .partitions.map(_.mkString("{", ",", "}"))
      .mkString("{", ",", "}")
}
