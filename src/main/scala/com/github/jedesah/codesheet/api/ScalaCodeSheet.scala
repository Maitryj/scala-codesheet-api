package com.github.jedesah.codesheet.api

import scala.reflect.runtime.{currentMirror => cm}
import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox
import scala.tools.reflect.ToolBoxError
import scala.reflect.runtime.universe.Flag._

import com.github.jedesah.Math
import com.github.jedesah.ScalaUtils._
import com.github.jedesah.AugmentedCollections._

object ScalaCodeSheet {

	val reservedName = "youcannotnameyourvariablelikethisincodesheet"
	val nameOfMap = reservedName + "_map"
	val nameOfTemp = reservedName + "_temp"

	def childrenRepr(at: Int, elems: List[{ def line: Int; def userRepr: String}]): String =
		elems.sortBy(_.line).foldLeft((at, "")) { case ((at, result), child) =>
			val newResult =
				if (at == child.line && result != "") result + "; " + child.userRepr
				else result + "\n" * (child.line - at) + child.userRepr
			(child.line, newResult)
		}._2

	/*def willNeedLater = { val singleChild = childrenRepr(at, children)
				if (singleChild.isEmpty) ""
				else if (!singleChild.contains("\n")) " " + singleChild
				else singleChild.tabulate}*/

	case class Result(subResults: List[StatementResult], output: String) {
		def userRepr = childrenRepr(1, subResults.asInstanceOf[List[{ def line: Int; def userRepr: String}]])
	}

	abstract class StatementResult(val line: Int) {
		def userRepr: String
		protected def asParamList(params: List[AssignOrNamedArg]) = params.mkStringNoEndsIfEmpty("(", ", ", ")")
		def transform(pf: PartialFunction[StatementResult, StatementResult]): StatementResult = {
			val sub = transformChildren(pf)
			pf.applyOrElse(sub, identity[StatementResult])
		}
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]): StatementResult
		def value: Option[Any]
		def wasEvaluated: Boolean
		def printAfterLineDiff(toPrint: ExpressionResult, customPrint: ExpressionResult => String = expr => expr.userRepr ) = {
			val lineDiff = toPrint.line - line
			"\n" * lineDiff + (if(lineDiff > 0) "\t" else " ") + customPrint(toPrint)
		}
	}
	abstract class ExpressionResult(override val line: Int) extends StatementResult(line)
	case class BlockResult(children: List[StatementResult], override val line: Int) extends ExpressionResult(line) {
		def userRepr = userRepr(Nil)
		def userRepr(implementedMembers: List[ValOrDefDef]): String = {
			case class WrappedValOrDefDef(valOrDefDef: ValOrDefDef) {
				def userRepr = valOrDefDef.toString
				def line = valOrDefDef.pos.line
			}
			val elemsToPrint = (children ++ implementedMembers.map(WrappedValOrDefDef(_))).asInstanceOf[List[{def line: Int; def userRepr: String}]]
			if (elemsToPrint.isEmpty) ""
			else {
				val childRepr = childrenRepr(line, elemsToPrint)
				if (childRepr.contains("\n")) "{" + childRepr.lines.toList.head + "\n" + childRepr.lines.toList.tail.map(_.tabulate).mkString("\n") + "\n}"
				else s"{$childRepr}"
			}
		}
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = copy(children = children.map(_.transform(pf)))
		def value = children.last.value
		def wasEvaluated = children.exists(_.wasEvaluated) || children.isEmpty
	}
	case class ValDefResult(name: String, inferredType: Option[String], rhs: ExpressionResult, override val line: Int) extends StatementResult(line) {
		def userRepr = name + inferredType.map(": " + _).getOrElse("") + " =" + printAfterLineDiff(rhs)
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = copy(rhs = rhs.transform(pf).asInstanceOf[ExpressionResult])
		def value = Some(())
		def wasEvaluated = rhs.wasEvaluated
	}
	private case class IfThenElseResultPlaceholder(cond: ExpressionResult, then: ExpressionResult, else_ : ExpressionResult, override val line: Int) extends ExpressionResult(line) {
		def value = if(isTrue) then.value else else_.value
		def isTrue = cond.value.get.asInstanceOf[Boolean]
		def userRepr = error("This should never be called")
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = {
			val newCond = cond.transform(pf).asInstanceOf[ExpressionResult]
			if (newCond.value.get.asInstanceOf[Boolean]) copy(cond = newCond, then = then.transform(pf).asInstanceOf[ExpressionResult])
			else copy(cond = newCond, else_ = else_.transform(pf).asInstanceOf[ExpressionResult])
		}
		def wasEvaluated = cond.wasEvaluated
	}
	case class IfThenElseResult(cond: ExpressionResult, executed: ExpressionResult, override val line: Int) extends ExpressionResult(line) {
		def value = executed.value
		def isTrue = cond.value.get.asInstanceOf[Boolean]
		def userRepr = printAfterLineDiff(executed, (expr) => (if (isTrue) "then => " else "else => ") + expr.userRepr)
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = {
			copy(cond = cond.transform(pf).asInstanceOf[ExpressionResult], executed = executed.transform(pf).asInstanceOf[ExpressionResult])
		}
		// This structured is used to represent an if branching once we now which branch was executed at runtime
		// so it was evaluated for sure unless we have a bug
		def wasEvaluated = { assert(cond.wasEvaluated); true; }
	}
	private case class MatchResultPlaceholder(selector: ExpressionResult, cases: List[ExpressionResult], override val line: Int) extends ExpressionResult(line) {
		def value = cases.find(_.wasEvaluated).get.value
		def userRepr = error("This should never be called")
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) =
			copy(selector = selector.transform(pf).asInstanceOf[ExpressionResult], cases = cases.map(_.transform(pf).asInstanceOf[ExpressionResult]))
		def wasEvaluated = selector.wasEvaluated
	}
	case class MatchResult(selector: ExpressionResult, matchedCase: ExpressionResult, override val line: Int) extends ExpressionResult(line) {
		def value = matchedCase.value
		def userRepr = selector.userRepr + " match {" + printAfterLineDiff(matchedCase, (expr) => "case => " + expr.userRepr) + "\n}"
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) =
			copy(selector = selector.transform(pf).asInstanceOf[ExpressionResult], matchedCase = matchedCase.transform(pf).asInstanceOf[ExpressionResult])
		def wasEvaluated = { assert(selector.wasEvaluated); true;}
	}
	case class DefDefResult(name: String, params: List[AssignOrNamedArg], inferredType: Option[String], rhs: ExpressionResult, override val line: Int) extends StatementResult(line) {
		def userRepr = name + asParamList(params) + inferredType.map(": " + _).getOrElse("") + " =>" + printAfterLineDiff(rhs)
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = copy(rhs = rhs.transform(pf).asInstanceOf[ExpressionResult])
		def value = Some(())
		def wasEvaluated = rhs.wasEvaluated
	}
	class ClassDefResult(val name: String, val params: List[AssignOrNamedArg], val body: BlockResult, override val line: Int) extends StatementResult(line) {
		def bodyRepr = body.userRepr
		def userRepr = "class " + name + asParamList(params) + (if (bodyRepr.isEmpty) "" else " " + bodyRepr)
		override def equals(other: Any) = other match { case classDefResult: ClassDefResult => equals(classDefResult) case _ => false }
		def equals(classDefResult: ClassDefResult) = name == classDefResult.name && params == classDefResult.params && body == classDefResult.body && line == classDefResult.line
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = copy(body = body.transform(pf).asInstanceOf[BlockResult])
		def copy(name: String = name, params: List[AssignOrNamedArg] = params, body: BlockResult = body, line: Int = line) = ClassDefResult(name, params, body, line)
		def value = Some(())
		def wasEvaluated = body.wasEvaluated
	}
	object ClassDefResult {
		def apply(name: String, params: List[AssignOrNamedArg], body: BlockResult, line: Int) = new ClassDefResult(name, params, body, line)
		def unapply(classDefResult: ClassDefResult) = Some((classDefResult.name, classDefResult.params, classDefResult.body, classDefResult.line))
	}
	case class AbstractClassDefResult(abstractMembers: List[ValOrDefDef], override val name: String, override val params: List[AssignOrNamedArg], override val body: BlockResult, override val line: Int)
	  extends ClassDefResult(name, params, body, line) {
		override def bodyRepr = body.userRepr(abstractMembers)
		override def userRepr = "abstract " + super.userRepr
	}
	case class ModuleDefResult(name: String, body: BlockResult, override val line: Int) extends StatementResult(line) {
		def userRepr = {
			val bodyString = body.userRepr
			if (bodyString.isEmpty) "" else name + " " + bodyString
		}
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]) = copy(body = body.transform(pf).asInstanceOf[BlockResult])
		def value = Some(())
		def wasEvaluated = body.wasEvaluated
	}
	trait NoChildren extends StatementResult {
		def transformChildren(pf: PartialFunction[StatementResult, StatementResult]): StatementResult = this
	}
	case class SimpleExpressionResult(final_ : ValueResult, steps: List[Tree] = Nil, override val line: Int) extends ExpressionResult(line) with NoChildren {
		def userRepr = (steps.map(_.prettyPrint) :+ final_.userRepr).mkString(" => ")
		def value = final_.valueOption
		def wasEvaluated = !final_.isInstanceOf[PlaceHolder]
	}
	case class CompileErrorResult(message: String, override val line: Int) extends StatementResult(line) with NoChildren {
		def userRepr = message
		def value = None
		def wasEvaluated = false
	}
	trait ValueResult {
		def userRepr: String
		def valueOption: Option[Any]
	}
	case class ExceptionValue(ex: Throwable) extends ValueResult {
		def userRepr = "throws " + ex
		def valueOption = Some(ex)
	}
	case object NotImplementedResult extends ValueResult {
		def userRepr = "???"
		def valueOption = Some(new NotImplementedError())
	}
	case class ObjectResult(value: Any) extends ValueResult {
		def userRepr = value match {
			case _: scala.runtime.BoxedUnit | scala.Unit => ""
			case _ => value.toString
		}
		def valueOption = Some(value)
	}
	private case class PlaceHolder(id: Int) extends ValueResult {
		def userRepr = "" // TODO: Probably want to put this back to throw an error
		def valueOption = None
	}
	implicit def createObjectResult(value: Any) = ObjectResult(value)

	val notImplSymbol = Ident(newTermName("$qmark$qmark$qmark"))

	def analyseAndTransform(AST: Tree, enableSteps: Boolean): (Tree, List[StatementResult]) = {

		var index = 0

		def wrapInTry(toWrap: Tree): Tree = Apply(Select(Select(Ident(newTermName("scala")), newTermName("util")), newTermName("Try")), List(toWrap))

		def evaluateValDef(AST: ValDef, classDefs: Traversable[ClassDef], topLevel: Boolean): (List[Tree], Option[ValDefResult]) = {
			if (AST.isSynthetic) (List(AST), None)
			else {
				val topLevelGoingForward = topLevel && !AST.isVar
				val (rhsTrees, rhsResult) = evaluateImpl(AST.rhs, classDefs, topLevelGoingForward)
				// The Scala AST does not type it, but you can only have an expression as a rhs of a ValDef
				// It's possible that we do not get any tree if we encounter something like a ??? that we do not need to evaluate in ordre to give a meaninfull result
				val trees = rhsTrees.headOption.map { rhsTree =>
					if (topLevelGoingForward) {
						val tempName = newTermName(reservedName + "_b_" + AST.name.toString)
						val wrappedTemp = ValDef(Modifiers(), tempName, TypeTree(), rhsTree)
						val asFunDef = DefDef(AST.mods, AST.name, List(), List(), AST.tpt, Select(Ident(tempName), newTermName("get")))
						List(wrappedTemp, asFunDef)
					}
					else {
						List(ValDef(AST.mods, AST.name, AST.tpt, rhsTree))
					}
				}.getOrElse(Nil)
				val result = ValDefResult(AST.name.toString, None, rhsResult.get.asInstanceOf[ExpressionResult], line = AST.pos.line)
				(trees, Some(result))
			}
		}
		def evaluateAssign(AST: Assign, classDefs: Traversable[ClassDef]): (Option[Assign], ValDefResult) = {
			val (rhsTrees, rhsResult) = evaluateImpl(AST.rhs, classDefs, false)
			val assign = rhsTrees.headOption.map { rhsTree =>
				Assign(AST.lhs, rhsTree)
			}
			val result = ValDefResult(AST.lhs.toString, None, rhsResult.get.asInstanceOf[ExpressionResult], line = AST.pos.line)
			(assign, result)
		}
		def evaluateIf(AST: If, classDefs: Traversable[ClassDef], topLevel: Boolean): (Tree, IfThenElseResultPlaceholder) = {
			val (thenTree, thenResult) = evaluateImpl(AST.thenp, classDefs, false)
			val (elseTree, elseResult) = evaluateImpl(AST.elsep, classDefs, false)
			val (condTree, condResult) = evaluateImpl(AST.cond, classDefs, false)
			val ifResult = IfThenElseResultPlaceholder(condResult.get.asInstanceOf[ExpressionResult], thenResult.get.asInstanceOf[ExpressionResult], elseResult.get.asInstanceOf[ExpressionResult], AST.pos.line)
			val ifTree = If(condTree.head, thenTree.head, elseTree.head)
			val resultTree = if (topLevel) wrapInTry(ifTree) else ifTree
			(resultTree, ifResult)
		}
		def evaluateMatch(AST: Match, classDefs: Traversable[ClassDef], topLevel: Boolean): (Tree, MatchResultPlaceholder) = {
			val (selectorTree, selectorResult) = evaluateImpl(AST.selector, classDefs, false)
			val (caseTrees, caseResults) = AST.cases.map(evaluateImpl(_, classDefs, false)).unzip
			val matchResult = MatchResultPlaceholder(selectorResult.get.asInstanceOf[ExpressionResult], caseResults.map(_.get.asInstanceOf[ExpressionResult]), AST.pos.line)
			val matchTree = Match(selectorTree.head, caseTrees.map(_.head.asInstanceOf[CaseDef]))
			val resultTree = if (topLevel) wrapInTry(matchTree) else matchTree
			(resultTree, matchResult)
		}
		def evaluateDefDef(AST: DefDef, classDefs: Traversable[ClassDef]): Option[(Option[Try], DefDefResult)] = {
			AST.sampleParamsOption(classDefs) map { sampleValues =>
				val (rhsTrees, rhsResult) = evaluateImpl(AST.rhs, classDefs, false)
				// The Scala AST does not type it, but you can only have an expression as a rhs of a ValDef
				// It's possible that we do not get any tree if we encounter something like a ??? that we do not need to evaluate in ordre to give a meaninfull result
				val block = rhsTrees.headOption.map { rhsTree =>
					Block(sampleValues, rhsTree)
				}
				val try_ = block.map(Try(_, List(CaseDef(Ident(nme.WILDCARD), EmptyTree, Literal(Constant(())))), EmptyTree))
				val defDefResult = DefDefResult(AST.name.toString, paramList(sampleValues), None, rhsResult.get.asInstanceOf[ExpressionResult], line = AST.pos.line)
				(try_, defDefResult)
			}
		}
		def evaluateBlock(AST: Block, classDefs: Traversable[ClassDef], topLevel: Boolean): (Tree, StatementResult) = {
			import shapeless.syntax.typeable._
			// This block was added by the Scala compiler while desaguring a Scala feature
			// For instance, when using an operator ending with a colon
			if (AST.children(0).cast[ValDef].map(_.isSynthetic).getOrElse(false) && AST.children.length == 2) {
				val (trees, resultOption) = evaluateImpl(AST.children(1), classDefs, false)
				// We return the same compiler generated block exept we instrumented
				// the expression the user is interested in
				// i.e we did not instrument the compiler generated code that would surprise the user
				(Block(List(AST.children(0)), trees.head), resultOption.get)
			}
			else {
				val additionnalClassDefs = AST.children.collect { case child: ClassDef => child }
				val (trees, results) = evaluateList(AST.children, classDefs, false)
				val newBlock = Block(trees.init, trees.last)
				val transformedTree = if (topLevel) wrapInTry(newBlock) else newBlock
				(transformedTree, BlockResult(results, AST.pos.line))
			}
		}
		def evaluateList(list: List[Tree], classDefs: Traversable[ClassDef], topLevel: Boolean) : (List[Tree], List[StatementResult]) = {
			val additionnalClassDefs = list.collect { case child: ClassDef => child }
			val (childTrees, childResults) = list.map(evaluateImpl(_, classDefs ++ additionnalClassDefs, topLevel)).unzip
			(childTrees.flatten, childResults.flatten)
		}
		def evaluateClassDef(AST: ClassDef, classDefs: Traversable[ClassDef]): Option[(Option[Block], ClassDefResult)] = {
			AST.constructorOption.flatMap { constructor =>
				constructor.sampleParamsOption(classDefs).map { sampleParams =>
					// We remove the value defintions resulting from the class parameters and the primary constructor
					val noSynthetic = AST.impl.body.filter {
						case valDef: ValDef => !sampleParams.exists( sampleValDef => sampleValDef.name == valDef.name)
						case other => other != constructor
					}
					val abstractMembers = noSynthetic.collect { case def_ : ValOrDefDef if def_.rhs.isEmpty => def_ }
					val implAbstractMembersOptions = abstractMembers.implementAbstractMembers(classDefs, createDefaultSamplePool)

					if (implAbstractMembersOptions.contains(None)) None
					else {
						val implementedMembers = implAbstractMembersOptions.flatten
						val noAbstract = noSynthetic.filter {
							case valDef: ValOrDefDef => !implementedMembers.exists( sampleValDef => sampleValDef.name == valDef.name)
							case other => other != constructor
						}
						val sampleValues = sampleParams ++ implementedMembers
						val (trees, results) = evaluateList(noAbstract, classDefs, false)
						val classDefResult = ClassDefResult(AST.name.toString, paramList(sampleParams), BlockResult(results, line = AST.pos.line), line = AST.pos.line)
						val blockOption = if (trees.isEmpty) None else Some(Block(sampleValues ++ trees, Literal(Constant(()))))
						Some(blockOption, classDefResult)
					}
				}
			}.flatten
		}
		def evaluateModuleDef(AST: ModuleDef, classDefs: Traversable[ClassDef]): (Block, ModuleDefResult) = {
			val body = AST.impl.body.filter(!isConstructor(_))
			val (trees, results) = evaluateList(body, classDefs, false)
			(Block(trees, Literal(Constant(()))), ModuleDefResult(AST.name.toString, BlockResult(results, line = AST.pos.line), line = AST.pos.line))
		}
		def evaluateImpl(AST: Tree, classDefs: Traversable[ClassDef], topLevel: Boolean): (List[Tree], Option[StatementResult]) = {
			AST match {
				case assign: Assign => {
					val (newAssignOption, valDefResult) = evaluateAssign(assign, classDefs)
					(newAssignOption.toList, Some(valDefResult))
				}
				case ifTree: If => {
					val (newIf, ifResult) = evaluateIf(ifTree, classDefs, topLevel)
					(List(newIf), Some(ifResult))
				}
				case matchTree: Match => {
					val (newMatch, matchResult) = evaluateMatch(matchTree, classDefs, topLevel)
					(List(newMatch), Some(matchResult))
				}
				case caseDef: CaseDef => {
					val (newBody, bodyResult) = evaluateImpl(caseDef.body, classDefs, false)
					(List(CaseDef(caseDef.pat, newBody.head)), bodyResult)
				}
				case valDef: ValDef => {
					val (newValDefOption, valDefResult) = evaluateValDef(valDef, classDefs, topLevel)
					(newValDefOption.toList, valDefResult)
				}
				case funDef: DefDef => {
					evaluateDefDef(funDef, classDefs).map { case (blockOption, defDefResult) =>
						(funDef :: blockOption.toList, Some(defDefResult))
					} getOrElse (List(funDef), None)
				}
				case block: Block => {
					val (newblock, blockResult) = evaluateBlock(block, classDefs, topLevel)
					(List(newblock), Some(blockResult))
				}
				case classDef: ClassDef => {
					evaluateClassDef(classDef, classDefs).map { case (blockOption, classDefResult) =>
						(classDef :: blockOption.toList, Some(classDefResult))
					} getOrElse (List(classDef), None)
				}
				case moduleDef: ModuleDef => {
					val (block, moduleDefResult) = evaluateModuleDef(moduleDef, classDefs)
					(List(moduleDef, block), Some(moduleDefResult))
				}
				case import_ : Import => (List(import_), None)
				case EmptyTree => (Nil, None)
				case expr => {
					val steps = Nil
					// This is where most of the magic happens!
					// We change the user's code to store evaluated expressions
					// before moving on with the rest of the program

					// We create a temporary value to store the result of the expression
					// We wrap the rhs in a Try monad to detect exceptions
					val tempName = newTermName(nameOfTemp)
					val tempVal = ValDef(Modifiers(), tempName, TypeTree(), wrapInTry(expr))
					val indexLiteral = Literal(Constant(index))
					val storeInMap = Apply(Select(Ident(newTermName(nameOfMap)), newTermName("update")), List(indexLiteral, Ident(tempName)))
					// We extract the actual value from the monad to preserve original program flow
					val ref = Ident(tempName)
					val extract = Select(ref, newTermName("get"))
					val lastExpr = if (topLevel) ref else extract
					val evalAndStore = Block(List(tempVal, storeInMap), lastExpr)
					val valueResult = PlaceHolder(index)
					index = index + 1
					(List(evalAndStore), Some(SimpleExpressionResult(final_ = valueResult, steps = steps, line = AST.pos.line)))
				}
			}
		}

		val beginMap =  ValDef(Modifiers(), newTermName(nameOfMap), TypeTree(), Apply(TypeApply(Select(Select(Select(Ident(newTermName("scala")), newTermName("collection")), newTermName("mutable")), newTermName("Map")), List(Ident(newTypeName("Int")), Ident(newTypeName("Any")))), List()))
		val retrieveMap = Ident(newTermName(nameOfMap))

		val (trees, results) = AST match {
			case block: Block if block.pos == NoPosition => evaluateList(block.children, Nil, topLevel = true)
			case _ => {
				val (trees, resultOption) = evaluateImpl(AST, Nil, topLevel = true)
				(trees, resultOption.toList)
			}
		}

		val transformedUserAST = wrapInTry(Block(trees, reify{ () }.tree))
		val transformedAST = Block(List(beginMap, transformedUserAST), retrieveMap)

		(transformedAST, results)
	}

	def computeResults(code: String, enableSteps: Boolean = true): Result = try {
		val toolBox = cm.mkToolBox()
		val AST = toolBox.parse(code)
		val (instrumented, statementResultsWithPlaceHolders) = analyseAndTransform(AST, enableSteps = enableSteps)
		val outputStream = new java.io.ByteArrayOutputStream()
		import scala.util.{Try, Success}
		val values = Console.withOut(outputStream) {
			toolBox.eval(instrumented).asInstanceOf[scala.collection.mutable.Map[Int, Try[Any]]]
		}
		// Take the results that were statically generated and update them now that we have the map that tells us
		// what happened at runtime.
		val statementResults = statementResultsWithPlaceHolders.map(_.transform {
			// Extract values from the map and insert them in SimpleExpressionResults
			case simple @ SimpleExpressionResult(PlaceHolder(id), _, _) =>
				values.get(id).map { value =>
					simple.copy(final_ = value.transform(
						value => Success(ObjectResult(value)),
						exception => Success(exception match {
							case ex: scala.NotImplementedError => NotImplementedResult
							case ex => ExceptionValue(exception)
						})
					).get)
				}.getOrElse(simple) // If no value was found, just return the structure unchanged,
									// it will probably get eliminated by the IfThenElse substitution
			// We do not know in advance which branch will be executed, but now we know, so update the structure to reflect this
			case result @IfThenElseResultPlaceholder(cond, then, else_, line) => {
				val executed = if (result.isTrue) then else else_
				IfThenElseResult(cond, executed, line)
			}
			case result @MatchResultPlaceholder(selector, cases, line) =>
				MatchResult(selector, cases.find(_.wasEvaluated).get, line)
			case block @BlockResult(children, _) => block.copy(children = children.filter(_.wasEvaluated))
		})
		Result(statementResults.filter(_.wasEvaluated), outputStream.toString)
	} catch {
		case ToolBoxError(msg, cause) => {
			val userMessage = msg.dropWhile(_ != ':').drop(2).dropWhile(char => char == ' ' || char == '\n' || char == '\t')
			if (code.lines.isEmpty) Result(Nil, "") else computeResults(code.lines.drop(1).mkString)
		}
	}

	implicit class DefDefWithSamples(defdef: DefDef) {
		def sampleParamsOption(classDefs: Traversable[ClassDef] = Nil, samplePool: SamplePool = createDefaultSamplePool): Option[List[ValDef]] = {
			def sequentialEagerTerminationImpl(valDefs: List[ValDef]): Option[List[ValDef]] = {
				if (valDefs.isEmpty) Some(Nil)
				else {
					valDefs.head.withSampleValue(classDefs, samplePool).map { newValDef =>
						sequentialEagerTerminationImpl(valDefs.tail).map { rest => newValDef :: rest }
					}.flatten
				}
			}
			val allParams = defdef.vparamss.flatten
			sequentialEagerTerminationImpl(allParams)
		}
	}

	implicit class ValDefWithSample(valDef: ValDef) {

		def withSampleValue(classDefs: Traversable[ClassDef] = Nil, samplePool: SamplePool = createDefaultSamplePool): Option[ValDef] =
			if (valDef.rhs.isEmpty)
				valDef.sampleValue(classDefs, samplePool).map { rhs =>
					atPos(valDef.pos)(ValDef(Modifiers(), valDef.name, TypeTree(), rhs))
				}
			else Some(valDef)

		def sampleValue(classDefs: Traversable[ClassDef] = Nil,	samplePool: SamplePool = createDefaultSamplePool): Option[Tree] =
			valDef.tpt.sampleValue(classDefs, samplePool)
	}

	implicit class BodyWithSample(value: List[ValOrDefDef]) {
		def implementAbstractMembers(classDefs: Traversable[ClassDef] = Nil,
		                             samplePool: SamplePool = createDefaultSamplePool): List[Option[ValOrDefDef]] =
			value.map {
				case valDef: ValDef => valDef.withSampleValue(classDefs, samplePool)
				case defDef: DefDef => defDef.tpt.sampleValue(classDefs, samplePool).map { sampleImpl =>
					DefDef(Modifiers(), defDef.name, List(), defDef.vparamss, TypeTree(), sampleImpl)
				}
			}
	}

	implicit class TreeWithSample(tree: Tree) {

		def sampleValue(classDefs: Traversable[ClassDef] = Nil,
                        samplePool: SamplePool = createDefaultSamplePool): Option[Tree] = {

			def assignValueOfCustomType: Option[Tree] = {
				val concretePred = (classDef: ClassDef) => {
					val isSameConcreteClass = classDef.name.toString == tree.toString && !classDef.isAbstract
					val isSubClass = classDef.impl.parents.exists( (parent: Tree) => { parent.toString == tree.toString})
					isSameConcreteClass || isSubClass
				}
				val abstractPred = (classDef: ClassDef) => {
					classDef.name.toString == tree.toString && classDef.isAbstract
				}
				// TODO: Consider possibility that an object could extend an abstract type and be of use here
				classDefs.find(concretePred).orElse(classDefs.find(abstractPred)).flatMap { classDef =>
					def assignAnonClass: Option[Tree] = {
						val implementedMembersOption: List[Option[ValOrDefDef]] =
							classDef.abstractMembers.implementAbstractMembers(classDefs, samplePool)
							if (implementedMembersOption.contains(None)) None
							else {
								val implementedMembers = implementedMembersOption.flatten
								Some(anonClass(classDef.name.toString, implementedMembers))
							}
					}
					if (classDef.isTrait) assignAnonClass
					else classDef.constructorOption.flatMap { constructorDef =>
						constructorDef.sampleParamsOption(classDefs, samplePool).flatMap { innerValues =>
							if (classDef.isAbstract) {
								// TODO: Handle case where abstract class takes parameters
								assignAnonClass
							}
							else if (classDef.isCaseClass)
								Some(Apply(Ident(newTermName(classDef.name.toString)), innerValues.map(_.rhs)))
							else
								Some(Apply(Select(New(Ident(newTypeName(classDef.name.toString))), nme.CONSTRUCTOR), innerValues.map(_.rhs)))
						}
					}
				}
			}
			tree match {
				case tpt: AppliedTypeTree => {
					val innerType = tpt.args(0)
					tpt.tpt.toString match {
						case "List" => {
							val length = samplePool.nextSeqLength
							if (length == 0)
								Some(Ident(newTermName("Nil")))
							else {
								val innerSamplesOpt = (0 until length).map{ useless => innerType.sampleValue(classDefs, samplePool)}
								if (innerSamplesOpt.exists(_.isEmpty)) None
								else {
									val innerSamples = innerSamplesOpt.map(_.get)
									val tree = Apply(Ident(newTermName("List")), innerSamples.toList)
									Some(tree)
								}
							}
						}
						case "Option" => {
							val isSome = samplePool.nextOptionIsSome
							if (isSome)
								innerType.sampleValue(classDefs, samplePool).map { innerValue =>
									Apply(Ident(newTermName("Some")), List(innerValue))
								}
							else
								Some(Ident(newTermName("None")))
							}
						case "Seq" => {
							val length = samplePool.nextSeqLength
							if (length == 0)
								Some(Ident(newTermName("Nil")))
							else {
								val innerSamplesOpt = (0 until length).map(useless => innerType.sampleValue(classDefs, samplePool))
								if (innerSamplesOpt.exists(_.isEmpty)) None
								else {
									val innerSamples = innerSamplesOpt.map(_.get)
									val tree = Apply(Ident(newTermName("Seq")), innerSamples.toList)
									Some(tree)
								}
							}
						}
						case _ => None
					}
				}
				case tpt: ExistentialTypeTree => {
					// TODO: Remove hard coding for only one type param
					// Probably fixed by removing the 0 index retrieval and the list construction
					val upperBound = tpt.whereClauses(0).asInstanceOf[TypeDef].rhs.asInstanceOf[TypeBoundsTree].hi
					// Let's exract the AppliedTypeTree from this Existential and replace the type argument
					// with the upper bound of the where clause
					val innerType = tpt.tpt.asInstanceOf[AppliedTypeTree]
					val simplified = AppliedTypeTree(innerType.tpt, List(upperBound))
					simplified.sampleValue(classDefs, samplePool)
				}
				case tpt: Select => samplePool.get(tpt.name.toString).orElse(assignValueOfCustomType)
				case tpt => samplePool.get(tpt.toString).orElse(assignValueOfCustomType)
			}
		}
	}

	def paramList(valDefs: List[ValDef]): List[AssignOrNamedArg] = {
		valDefs.map(valDef => AssignOrNamedArg(Ident(valDef.name), valDef.rhs))
	}

	// I don't like the fact that 2 + 3 = 5 which is why I'd rather
	// start the sample Int values at 3 instead of at 2 in the primeNumbers list

	val sampleIntValues = Math.primeNumbers.drop(1).map(_.toString)
	val sampleStringValues = "\"foo\"" #:: "\"bar\"" #:: "\"biz\"" #:: "\"says\"" #:: "\"Hello\"" #:: "\"World\"" #:: "\"bazinga\"" #:: Stream.empty repeat 
	val alphabet = "abcdefghijklmnopqrstuvwxyz"
	val sampleCharValues = (0 until alphabet.length).map(alphabet.charAt(_)).toStream.repeat.map("\'" + _ + "\'")
	val sampleDoubleValues = Math.primeNumbers.map(_ - 0.5).map(_.toString)
	val sampleFloatValues = sampleDoubleValues.map(_ + "f")
	val sampleBooleanValues = (true #:: false #:: Stream.empty).repeat.map(_.toString)
	//val sampleAnyValValues = sampleIntValues.zip5(sampleStringValues, sampleFloatValues, sampleBooleanValues, sampleCharValues).flatten(_.productIterator)
	val sampleAnyValValues = "3" #:: "\'f\'" #:: "true" #:: Stream.empty repeat
	val sampleAnyValues = "3" #:: "\"foo\"" #:: "true" #:: Stream.empty repeat
	val sampleAnyRefValues = "\"foo\"" #:: "List(3,5,7)" #:: "Some(5)" #:: Stream.empty repeat

	val sampleSeqLengths = 3 #:: 0 #:: 1 #:: 2 #:: Stream.empty repeat
	val sampleSomeOrNone = true #:: false #:: Stream.empty repeat

	val simpleValues = Map(
		"Int" -> sampleIntValues,
		"String" -> sampleStringValues,
		"Float" -> sampleFloatValues,
		"Boolean" -> sampleBooleanValues,
		"Long" -> sampleIntValues,
		"Double" -> sampleDoubleValues,
		"Byte" -> sampleIntValues,
		"Short" -> sampleIntValues,
		"Char" -> sampleCharValues,
		"AnyVal" -> sampleAnyValValues,
		"Any" -> sampleAnyValues,
		"AnyRef" -> sampleAnyRefValues
	)

	val parsedSimpleValues = simpleValues.mapValues(stream => stream.map(cm.mkToolBox().parse(_)))
	def createDefaultSamplePool = new SamplePool(parsedSimpleValues, sampleSeqLengths, sampleSomeOrNone)
}

class SamplePool(var values: Map[String, Stream[Tree]], var seqLengths: Stream[Int], var someOrNone: Stream[Boolean]) {

	def get(type_ : String): Option[Tree] =
		values.get(type_).map { stream => // TODO: Remove the race condition here that might cause problems in the future with parallelization
			values = values.updated(type_, stream.tail)
			stream.head
		}

	def nextSeqLength: Int = {
		val result = seqLengths.head
		seqLengths = seqLengths.tail
		result
	}

	def nextOptionIsSome: Boolean = {
		val result = someOrNone.head
		someOrNone = someOrNone.tail
		result
	}
}