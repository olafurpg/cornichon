package com.github.agourlay.cornichon.steps.wrapped

import cats.data.Xor
import cats.data.Xor._
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.json.CornichonJson
import com.github.agourlay.cornichon.util.Formats
import com.github.agourlay.cornichon.core.Engine._
import com.github.agourlay.cornichon.core.Done._
import com.github.agourlay.cornichon.util.Timing._

import scala.concurrent.{ ExecutionContext, Future }

case class WithDataInputStep(nested: Vector[Step], where: String) extends WrapperStep {

  val title = s"With data input block $where"

  override def run(engine: Engine)(initialRunState: RunState)(implicit ec: ExecutionContext) = {

    def runInputs(inputs: List[List[(String, String)]], runState: RunState): Future[(RunState, Xor[FailedStep, Done])] = {
      if (inputs.isEmpty) Future.successful(runState, rightDone)
      else {
        val currentInputs = inputs.head
        val runInfo = InfoLogInstruction(s"Run with inputs ${Formats.displayTuples(currentInputs)}", runState.depth)
        val boostrapFilledInput = runState.withSteps(nested).addToSession(currentInputs).withLog(runInfo).goDeeper
        engine.runSteps(boostrapFilledInput).flatMap {
          case (filledState, stepsResult) ⇒
            stepsResult match {
              case Right(_) ⇒
                // Logs are propogated but not the session
                runInputs(inputs.tail, runState.appendLogs(filledState.logs))
              case Left(failedStep) ⇒
                // Prepend previous logs
                Future.successful(runState.withSession(filledState.session).appendLogs(filledState.logs), left(failedStep))
            }
        }
      }
    }

    Xor.catchNonFatal(CornichonJson.parseDataTable(where)).fold(
      t ⇒ Future.successful(exceptionToFailureStep(this, initialRunState, CornichonError.fromThrowable(t))),
      parsedTable ⇒ {
        val inputs = parsedTable.map { line ⇒
          line.toList.map { case (key, json) ⇒ (key, CornichonJson.jsonStringValue(json)) }
        }

        withDuration {
          runInputs(inputs, initialRunState.withSteps(nested).resetLogs.goDeeper)
        }.map {
          case (run, executionTime) ⇒

            val (inputsState, inputsRes) = run
            val initialDepth = initialRunState.depth

            val (fullLogs, xor) = inputsRes match {
              case Right(_) ⇒
                val fullLogs = successTitleLog(initialDepth) +: inputsState.logs :+ SuccessLogInstruction(s"With data input succeeded for all inputs", initialDepth, Some(executionTime))
                (fullLogs, rightDone)
              case Left(failedStep) ⇒
                val fullLogs = failedTitleLog(initialDepth) +: inputsState.logs :+ FailureLogInstruction(s"With data input failed for one input", initialDepth, Some(executionTime))
                val artificialFailedStep = FailedStep(failedStep.step, RetryMaxBlockReachedLimit)
                (fullLogs, left(artificialFailedStep))
            }

            (initialRunState.withSession(inputsState.session).appendLogs(fullLogs), xor)
        }
      }
    )
  }
}
