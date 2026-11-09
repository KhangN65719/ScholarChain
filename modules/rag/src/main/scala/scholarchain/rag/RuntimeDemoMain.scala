package scholarchain.rag

import scholarchain.core.VerificationStatus

/** Runs a few questions over the bundled demo world, offline: local fakes and
  * the deterministic stub model, no network and no credentials. For each
  * question it prints the answer, the claims with their statuses, the
  * verification verdict and the audit hash. Run it with
  * `sbt "rag/runMain scholarchain.rag.RuntimeDemoMain"` in a human session.
  */
object RuntimeDemoMain:
  val Questions: Vector[String] = Vector(
    "Which researcher has the most distinct coauthors from 2000-2009?",
    "What is the maximum team size of Ada Quillfeather from 2000-2009?",
    "How is Ada Quillfeather connected to Chidi Okonkwo from 2000-2009?",
    "Is Ada Quillfeather connected to Dana Whitcombe from 2000-2009?",
    "Compare the community and bridge position of Boris Marlowe in 2000-2009",
    "How many publications does Wei Zhang have?"
  )

  def render(question: String, result: WorkflowResult): String =
    val claims = result.response.claims
      .map { c =>
        val status = c.verificationStatus match
          case VerificationStatus.Verified    => "verified"
          case VerificationStatus.Uncertain   => "uncertain"
          case VerificationStatus.Unsupported => "unsupported"
        s"  - [$status] ${c.text} (evidence: ${c.evidenceIds.size})"
      }
      .mkString("\n")
    val verdict = result.verdict.map(_.wire).getOrElse("n/a (abstained)")
    s"""Question: $question
       |Answer:
       |${result.response.answer.linesIterator.map("  " + _).mkString("\n")}
       |Claims:
       |${
        if claims.isEmpty then "  (none)" else claims
      }
       |Verdict: $verdict; abstained: ${result.abstained}
       |Audit: answerHash=${result.audit.answerHash} bundle=${result.response.evidence.bundleId}
       |""".stripMargin

  def main(args: Array[String]): Unit =
    val world = DemoWorld.build()
    val workflow = world.workflow
    Questions.zipWithIndex.foreach { (question, i) =>
      val result = workflow.answer(question, Session("demo-session", i))
      println(render(question, result))
    }
