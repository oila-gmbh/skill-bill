package skillbill.db.workflow

import skillbill.review.model.ReviewRunId
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId
import java.sql.PreparedStatement

internal fun PreparedStatement.setString(index: Int, value: WorkflowId) = setString(index, value.value)

internal fun PreparedStatement.setString(index: Int, value: SessionId) = setString(index, value.value)

internal fun PreparedStatement.setString(index: Int, value: IssueKey) = setString(index, value.value)

internal fun PreparedStatement.setString(index: Int, value: ReviewRunId) = setString(index, value.value)

internal fun PreparedStatement.setInt(index: Int, value: SubtaskId) = setInt(index, value.value)
