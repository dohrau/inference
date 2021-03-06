/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference

object Names {
  /**
   * The prefix used to generate names for precondition placeholders.
   */
  val precondition = "pre"

  /**
   * The prefix used to generate names for postcondition placeholders.
   */
  val postcondition = "post"

  /**
   * The prefix used to generate names for invariant placeholders.
   */
  val invariant = "inv"

  /**
   * The prefix used to generate names for snapshots.
   */
  val snapshot = "s"

  /**
   * The prefix used to generate names for auxiliary variables.
   */
  val auxiliary = "t"

  /**
   * The name used for the recursive predicate.
   */
  val recursive = "P"

  /**
   * The name used for the append lemma.
   */
  val appendLemma = "append_lemma"

  /**
   * The name used for the concat lemma.
   */
  val concatLemma = "concat_lemma"

  /**
   * The name of the append annotation.
   */
  val appendAnnotation = "__append__"

  /**
   * The name of the concat annotation.
   */
  val concatAnnotation = "__concat__"

  /**
   * The name of the field that can be added to a Silicon program in order to enable fold heuristics.
   */
  val siliconHeuristics = "__CONFIG_HEURISTICS"
  /**
   * The name of the method that can be used to trigger a state consolidation in Silicon.
   */
  val siliconConsolidate = "___silicon_hack510_consolidate_state"

  /**
   * All annotation names.
   */
  val annotations = Seq(appendAnnotation, concatAnnotation)

  /**
   * Returns whether the given name corresponds to the recursive predicate.
   *
   * @param name The name to check.
   * @return True if the name corresponds to the recursive predicate.
   */
  def isRecursive(name: String): Boolean =
    name == recursive

  /**
   * Returns whether the given name corresponds to a annotation.
   *
   * @param name The name to check.
   * @return True if the name corresponds to a annotation.
   */
  def isAnnotation(name: String): Boolean =
    annotations.contains(name)

  /**
   * Returns the variable name used for the activation of a clause.
   *
   * @param guardId     The guard id.
   * @param clauseIndex The clause index.
   * @return The variable name.
   */
  @inline
  def clauseActivation(guardId: Int, clauseIndex: Int): String =
    s"x-$guardId-$clauseIndex"

  /**
   * Returns the variable name used for the activation of a literal.
   *
   * @param guardId      The guard id.
   * @param clauseIndex  The clause index.
   * @param literalIndex The literal index.
   * @return The variable name.
   */
  @inline
  def literalActivation(guardId: Int, clauseIndex: Int, literalIndex: Int): String =
    s"y-$guardId-$clauseIndex-$literalIndex"

  /**
   * Returns the variable name used for the sign of a literal.
   *
   * @param guardId      The guard id.
   * @param clauseIndex  The clause index.
   * @param literalIndex The literal index.
   * @return The variable name.
   */
  @inline
  def literalSign(guardId: Int, clauseIndex: Int, literalIndex: Int): String =
    s"s-$guardId-$clauseIndex-$literalIndex"

  /**
   * Returns the variable name used for the activation of the given choice.
   *
   * @param choiceId The choice id.
   * @param index    The index of the choice.
   * @return The variable name.
   */
  def choiceActivation(choiceId: Int, index: Int): String =
    s"c-$choiceId-$index"
}
