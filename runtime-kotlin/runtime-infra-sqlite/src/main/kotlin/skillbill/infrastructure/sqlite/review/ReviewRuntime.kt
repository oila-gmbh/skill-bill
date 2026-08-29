package skillbill.infrastructure.sqlite.review

import skillbill.review.ReviewParser
import skillbill.review.model.FindingMetadata
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewSummary
import java.sql.Connection
import java.sql.SQLException

object ReviewRuntime {
  fun parseReview(text: String): ImportedReview = ReviewParser.parseReview(text)

  fun saveImportedReview(connection: Connection, review: ImportedReview, sourcePath: String?) {
    connection.autoCommit = false
    try {
      persistImportedReview(connection, review, sourcePath)
      connection.commit()
    } catch (error: SQLException) {
      connection.rollback()
      throw error
    } finally {
      connection.autoCommit = true
    }
  }

  fun fetchImportedFindings(connection: Connection, reviewRunId: String): List<ImportedFinding> =
    connection.prepareStatement(importedFindingsSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(resultSet.toImportedFinding())
          }
        }
      }
    }

  fun fetchReviewSummary(connection: Connection, reviewRunId: String): ReviewSummary =
    connection.prepareStatement(reviewSummarySql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        require(resultSet.next()) { "Unknown review run id '$reviewRunId'." }
        resultSet.toReviewSummary()
      }
    }

  fun fetchFindingMetadata(connection: Connection, reviewRunId: String, findingId: String): FindingMetadata =
    connection.prepareStatement(findingMetadataSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, findingId)
      statement.executeQuery().use { resultSet ->
        require(resultSet.next()) { "Unknown finding id '$findingId' for review run '$reviewRunId'." }
        FindingMetadata(
          findingId = resultSet.getString("finding_id"),
          severity = resultSet.getString("severity"),
          confidence = resultSet.getString("confidence"),
        )
      }
    }

  fun fetchNumberedFindings(connection: Connection, reviewRunId: String): List<NumberedFinding> {
    require(reviewExists(connection, reviewRunId)) { "Unknown review run id '$reviewRunId'." }
    return connection.prepareStatement(numberedFindingsSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        buildList {
          var index = 1
          while (resultSet.next()) {
            add(resultSet.toNumberedFinding(index++))
          }
        }
      }
    }
  }

  /**
   * A run exists once its review text has been imported. The lane recorders reserve the parent row
   * before the text lands (`raw_text = ''`) to keep their foreign key honest, and a run that never
   * completed its import must not read as a reviewable run to triage or stats.
   */
  fun reviewExists(connection: Connection, reviewRunId: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM review_runs WHERE review_run_id = ? AND raw_text != ''",
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }

  fun findingExists(connection: Connection, reviewRunId: String, findingId: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM findings WHERE review_run_id = ? AND finding_id = ?").use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, findingId)
      statement.executeQuery().use { resultSet -> resultSet.next() }
    }
}
