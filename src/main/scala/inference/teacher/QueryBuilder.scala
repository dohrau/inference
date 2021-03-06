/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.teacher

import inference.Names
import inference.builder.CheckExtender
import inference.core.{Hypothesis, Instance}
import inference.input._
import inference.util.ast.{Expressions, InstanceInfo, Statements}
import inference.util.Namespace
import inference.util.collections.{Collections, SeqMap}
import viper.silver.ast

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * A query builder mixin.
 */
trait QueryBuilder extends CheckExtender[ast.Method] {
  /**
   * Returns the configuration.
   *
   * @return The configuration.
   */
  private def configuration: Configuration =
    input.configuration

  /**
   * The namespace used to generate unique identifiers.
   */
  private var namespace: Namespace = _

  /**
   * The partial query.
   */
  private var query: PartialQuery = _

  /**
   * Builds a query that checks whether the specifications represented by the given hypothesis are self-framing.
   *
   * @param hypothesis The hypothesis to check.
   * @return The framing query.
   */
  protected def framingQuery(hypothesis: Hypothesis): Query = {
    /**
     * Helper method that inhales the given expression conjunct-wise. The expression is implicitly rewritten to have
     * its conjuncts at the top level by pushing implications inside.
     *
     * @param expression The expression to inhale.
     * @param guards     The guards collected so far.
     */
    def inhale(expression: ast.Exp, guards: Seq[ast.Exp] = Seq.empty): Unit =
      expression match {
        case ast.TrueLit() => // do nothing
        case ast.And(left, right) =>
          inhale(left, guards)
          inhale(right, guards)
        case ast.Implies(guard, guarded) =>
          inhale(guarded, guards :+ guard)
        case conjunct =>
          // inhale conjunct
          val condition = Expressions.makeAnd(guards)
          val implication = ast.Implies(condition, conjunct)()
          emitInhale(implication)
      }

    // reset
    reset()

    // create predicates (dummy for recursive predicate)
    val predicates = {
      // get existing predicates
      val existing = input
        .program
        .predicates
        .filter(_.body.isDefined)
      // get recursive predicate
      val recursive = hypothesis
        .predicates
        .flatMap { predicate =>
          if (Names.isRecursive(predicate.name)) {
            val dummy = predicate.copy(body = None)(predicate.pos, predicate.info, predicate.errT)
            Some(dummy)
          } else
            None
        }
      // combine predicates
      existing ++ recursive
    }

    // create methods (one for each specification)
    val methods = input
      .placeholders
      .filter(_.isSpecification)
      .map { placeholder =>
        val parameters = placeholder.parameters
        // create body inhaling the specification
        val instrumented = {
          val body = makeScope {
            // save state snapshot
            val instance = placeholder.asInstance
            saveSnapshot(instance)
            // branch on accesses
            if (configuration.useBranching) {
              branch(instance)
            }
            // inhale inferred specification
            val inferred = hypothesis.getInferred(instance)
            inhale(inferred)
            // process existing specifications
            val existing = instance.existing
            if (existing.nonEmpty) {
              // replace predicate instances with their body to expose permissions that may be required to frame the
              // existing specification
              expose(inferred)(hypothesis)
              // inhale existing specifications
              existing.foreach { specification => inhale(specification) }
            }
          }
          Statements.makeDeclared(body, parameters)
        }
        // create method
        val name = namespace.uniqueIdentifier(name = s"check_${placeholder.name}", None)
        ast.Method(name, parameters, Seq.empty, Seq.empty, Seq.empty, Some(instrumented))()
      }

    // create program
    val original = input.program
    val program = original.copy(
      predicates = predicates,
      methods = methods
    )(original.pos, original.info, original.errT)

    // finalize query
    query(program, hypothesis)
  }

  /**
   * Builds a query based on the given batch of checks and hypothesis.
   *
   * @param batch      The batch of checks.
   * @param hypothesis The hypothesis to check.
   * @return The query.
   */
  protected def basicQuery(batch: Seq[Check], hypothesis: Hypothesis): Query = {
    // reset and get original program
    reset()
    val original = input.program

    // create predicates
    val predicates = {
      // get existing predicates
      val existing = input
        .program
        .predicates
        .filter(_.body.isDefined)
      // get inferred predicates
      val inferred = input
        .placeholders
        .flatMap { placeholder =>
          if (placeholder.isSpecification) {
            val predicate = hypothesis.getPredicate(placeholder)
            Some(predicate)
          } else {
            None
          }
        }
      // combine predicates
      existing ++ inferred
    }

    // create methods
    val methods = {
      // dummy methods
      val dummies = {
        // state consolidation method
        val consolidate =
          if (configuration.explicitConsolidation) Seq(consolidateMethod)
          else Seq.empty
        // methods for method not contained in batch
        val names = batch.map(_.name).toSet
        val unchecked = original
          .methods
          .flatMap { method =>
            if (names.contains(method.name)) None
            else {
              val dummy = method.copy(body = None)(method.pos, method.info, method.errT)
              Some(dummy)
            }
          }
        // concatenate all dummy methods
        consolidate ++ unchecked
      }
      // lemma methods
      val lemmas = hypothesis.lemmas
      // instrument methods
      implicit val current: Hypothesis = hypothesis
      val extended = batch.map(extendCheck)
      // combine lemma, dummy and instrumented methods
      dummies ++ lemmas ++ extended
    }

    // create program
    val program = original.copy(
      predicates = predicates,
      methods = methods
    )(original.pos, original.info, original.errT)

    // finalize query
    query(program, hypothesis)
  }

  /**
   * Resets the query builder.
   */
  private def reset(): Unit = {
    namespace = new Namespace()
    query = new PartialQuery
  }

  override protected def processCheck(check: Check)(implicit hypothesis: Hypothesis): ast.Method =
    check match {
      case MethodCheck(original, _, _, body) =>
        // instrument body
        val instrumented = {
          val extended = extendSequence(body)
          Statements.makeDeclared(extended, original.scopedDecls)
        }
        // build method based on original
        original.copy(
          pres = Seq.empty,
          posts = Seq.empty,
          body = Some(instrumented)
        )(original.pos, original.info, original.errT)
      case LoopCheck(_, name, _, body) =>
        // instrument loop
        val instrumented = {
          val extended = extendSequence(body)
          Statements.makeDeclared(extended)
        }
        // build method
        ast.Method(name, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Some(instrumented))()
    }

  override protected def processInstrumented(statement: ast.Stmt)(implicit hypothesis: Hypothesis, annotations: Seq[Annotation]): Unit =
    statement match {
      case ast.Seqn(statements, _) =>
        statements.foreach(processInstrumented)
      case ast.Inhale(expression) =>
        expression match {
          case ast.PredicateAccessPredicate(predicate, _) =>
            // get and inhale specification instance
            val instance = input.instance(predicate)
            inhaleInstance(instance)
          case condition =>
            emitInhale(condition)
        }
      case ast.Exhale(expression) =>
        expression match {
          case ast.PredicateAccessPredicate(predicate, _) =>
            // get and exhale specification instance
            val instance = input.instance(predicate)
            exhaleInstance(instance)
          case condition =>
            emitExhale(condition)
        }
      case other =>
        emit(other)
    }

  override protected def processCut(cut: Cut)(implicit hypothesis: Hypothesis): Unit = {
    // havoc written variables
    val written = cut.loop.original.writtenVars
    val havoc = Statements.makeHavoc(written)
    emit(havoc)
  }

  /**
   * Inhales the given specification instance.
   *
   * @param instance   The instance.
   * @param hypothesis The implicitly passed current hypothesis.
   */
  private def inhaleInstance(instance: Instance)(implicit hypothesis: Hypothesis, hints: Seq[Annotation]): Unit = {
    // get inferred specifications
    val inferred = hypothesis.getInferred(instance)
    // inhale specification
    val depth = configuration.unfoldDepth
    val inhales = commented(instance.toString) {
      // inhale inferred specification
      emitInhale(inferred)
      // unfold inferred specification
      if (configuration.querySimplification) simplified(unfold(inferred, depth))
      else unfold(inferred, depth)
      // handle existing specification
      instance
        .existing
        .foreach { condition => emitInhale(condition) }
    }
    emit(inhales)
    // lazily compute leaves
    lazy val leaves = collectLeaves(inferred, depth)
    // branch on accesses
    if (configuration.useBranching) {
      branch(instance, leaves)
    }
    // consolidate state if enabled
    if (configuration.assumeConsolidation) {
      consolidateAssume(inferred, depth)
    } else if (configuration.explicitConsolidation) {
      consolidateExplicit(leaves)
    }
    // save state snapshot
    saveSnapshot(instance)
  }

  /**
   * Exhales the given specification instance.
   *
   * @param instance   The instance.
   * @param hypothesis The implicitly passed current hypothesis.
   */
  private def exhaleInstance(instance: Instance)(implicit hypothesis: Hypothesis, hints: Seq[Annotation]): Unit = {
    // save state snapshot
    saveSnapshot(instance, exhaled = true)
    // exhale specification
    val exhales = commented(instance.toString) {
      // handle existing specification
      instance
        .existing
        .foreach { condition => emitExhale(condition) }
      // get inferred specification
      val inferred = hypothesis.getInferred(instance)
      // fold and exhale inferred specification
      val depth = configuration.foldDepth
      implicit val info: ast.Info = InstanceInfo(instance)
      if (configuration.querySimplification) simplified(exhale(inferred, depth))
      else exhale(inferred, depth)
    }
    emit(exhales)
  }

  /**
   * Saves a snapshot of the given instance.
   *
   * @param instance The instance.
   * @param exhaled  The flag indicating whether the snapshot was exhaled or not.
   */
  private def saveSnapshot(instance: Instance, exhaled: Boolean = false): Unit = {
    // generate unique snapshot label
    val label = namespace.uniqueIdentifier(Names.snapshot)
    query.addSnapshot(label, instance, exhaled)
    // save values of variables
    instance
      .arguments
      .foreach { argument =>
        if (argument.isSubtype(ast.Ref)) {
          argument match {
            case literal: ast.NullLit =>
              literal
            case variable: ast.LocalVar =>
              val name = s"${label}_${variable.name}"
              emitAssignment(name, variable)
            case other =>
              sys.error(s"Unexpected argument to instance: $other")
          }
        }
      }
    // emit label
    emitLabel(label)
  }

  /**
   * Collects all leaf access predicates, i.e., the access predicates present in the state after unfolding the
   * specification at hand. Each access predicate maps to the condition under which it is present in the sate.
   *
   * @param expression The expression representing the specification.
   * @param depth      The depth.
   * @param guards     The current guards.
   * @param hypothesis The current hypothesis.
   * @return The map containing all leaves.
   */
  private def collectLeaves(expression: ast.Exp, depth: Int, guards: Seq[ast.Exp] = Seq.empty)
                           (implicit hypothesis: Hypothesis): Map[ast.AccessPredicate, Seq[ast.Exp]] =
    expression match {
      case ast.And(left, right) =>
        val leftMap = collectLeaves(left, depth, guards)
        val rightMap = collectLeaves(right, depth, guards)
        SeqMap.merge(leftMap, rightMap)
      case ast.Implies(left, right) =>
        val updatedGuards = guards :+ left
        collectLeaves(right, depth, updatedGuards)
      case ast.PredicateAccessPredicate(predicate, _) if depth > 0 && Names.isRecursive(predicate.predicateName) =>
        val instance = input.instance(predicate)
        val nested = hypothesis.getInferred(instance)
        collectLeaves(nested, depth - 1, guards)
      case leaf: ast.AccessPredicate =>
        val condition = Expressions.makeAnd(guards)
        Map(leaf -> Seq(condition))
      case _ =>
        Map.empty
    }

  /**
   * Branches on atomic predicates that can be formed from the accesses appearing in the given instance and collected
   * leaves.
   *
   * @param instance The instance.
   * @param leaves   The map containing all leaves.
   */
  private def branch(instance: Instance, leaves: => Map[ast.AccessPredicate, Seq[ast.Exp]] = Map.empty): Unit = {
    // variables appearing in instance
    val variables = instance
      .arguments
      .filter(_.isSubtype(ast.Ref))
      .map { argument => argument -> ast.TrueLit()() }
    // dummy statement
    val dummy = makeScope(emitInhale(ast.TrueLit()()))
    // branch on nullity
    if (configuration.useNullityBranching) {
      variables.foreach {
        case (access, effective) =>
          val atom = ast.NeCmp(access, ast.NullLit()())()
          val condition = ast.And(effective, atom)()
          emitConditional(condition, dummy)
      }
    }
    // branch on equality
    if (configuration.useEqualityBranching) {
      // collected field accesses
      val fields = leaves.collect {
        case (ast.FieldAccessPredicate(access, _), conditions) if access.isSubtype(ast.Ref) =>
          access -> Expressions.makeOr(conditions)
      }
      // combine variables and fields
      val accesses = variables ++ fields
      // go through all pairs and branch equality
      Collections.pairs(accesses).foreach {
        case ((access1, effective1), (access2, effective2)) =>
          val atom = ast.NeCmp(access1, access2)()
          val condition = Expressions.makeAnd(Seq(effective1, effective2, atom))
          emitConditional(condition, dummy)
      }
    }
  }

  /**
   * Explicitly consolidates the state.
   *
   * @param leaves The map containing all leaves.
   */
  private def consolidateExplicit(leaves: => Map[ast.AccessPredicate, Seq[ast.Exp]]): Unit = {
    // collect leaf predicates
    val predicates = leaves.collect {
      case (predicate: ast.PredicateAccessPredicate, conditions) =>
        predicate -> Expressions.makeOr(conditions)
    }
    // unfold leaf predicates
    predicates.foreach {
      case (predicate, condition) =>
        val unfold = ast.Unfold(predicate)()
        emitConditional(condition, unfold)
    }
    // consolidate state
    emitConsolidate()
    // fold leaf predicates
    predicates.foreach {
      case (predicate, condition) =>
        val fold = ast.Fold(predicate)()
        emitConditional(condition, fold)
    }
  }

  /**
   * Consolidates the state after inhaling the given expression using assumptions.
   *
   * @param expression The inhaled expression.
   * @param depth      The depth up to which the expression was unfolded.
   * @param hypothesis The current hypothesis.
   */
  private def consolidateAssume(expression: ast.Exp, depth: Int)(implicit hypothesis: Hypothesis): Unit = {
    /**
     * Helper method that collects all predicate roots appearing in the given expression.
     *
     * @param expression The expression.
     * @param depth      The depth up to which the roots should be collected.
     * @param guards     The current guards.
     * @return The roots.
     */
    def collectRoots(expression: ast.Exp, depth: Int, guards: Seq[ast.Exp] = Seq.empty): Seq[(ast.Exp, ast.Exp)] =
      expression match {
        case ast.And(left, right) =>
          val collectedLeft = collectRoots(left, depth, guards)
          val collectedRight = collectRoots(right, depth, guards)
          collectedLeft ++ collectedRight
        case ast.Implies(left, right) =>
          val updatedGuards = guards :+ left
          collectRoots(right, depth, updatedGuards)
        case ast.PredicateAccessPredicate(predicate, _) =>
          // recursively collect roots
          val collected =
            if (depth > 0) {
              val instance = input.instance(predicate)
              val nested = hypothesis.getInferred(instance)
              collectRoots(nested, depth - 1, guards)
            } else {
              Seq.empty
            }
          // root corresponding to current predicate
          val root = {
            val arguments = predicate.args
            val first = arguments.head
            val second = arguments
              .tail
              .headOption
              .getOrElse(ast.NullLit()())
            val nonempty = ast.NeCmp(first, second)()
            val condition = Expressions.makeAnd(guards :+ nonempty)
            (first, condition)
          }
          // return all roots
          root +: collected
        case _ =>
          Seq.empty
      }

    val roots = collectRoots(expression, depth)
    Collections
      .pairs(roots)
      .foreach {
        case ((root1, condition1), (root2, condition2)) =>
          val inequality = ast.NeCmp(root1, root2)()
          val condition = ast.And(condition1, condition2)()
          val fact = ast.Implies(condition, inequality)()
          emitInhale(fact)
      }
  }
}

/**
 * A partial query.
 */
private class PartialQuery {
  /**
   * The buffer used to accumulate the snapshots.
   */
  private val snapshots: mutable.Buffer[(String, Instance, Boolean)] =
    ListBuffer.empty

  /**
   * Adds a snapshot, i.e., associates the given name with the given placeholder instance.
   *
   * @param label    The label of the snapshot.
   * @param instance The instance saved by the snapshot.
   * @param exhaled  The flag indicating whether the snapshot was exhaled or not.
   */
  def addSnapshot(label: String, instance: Instance, exhaled: Boolean): Unit =
    snapshots.append((label, instance, exhaled))

  /**
   * Finalizes the query with the given program.
   *
   * @param program    The program.
   * @param hypothesis The current hypothesis.
   * @return The finalized query.
   */
  def apply(program: ast.Program, hypothesis: Hypothesis): Query =
    new Query(program, hypothesis, snapshots.toSeq)
}
