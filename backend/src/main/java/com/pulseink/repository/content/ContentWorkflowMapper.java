package com.pulseink.repository.content;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentWorkflowMapper {

    @Insert("""
            INSERT INTO content_item(run_id, task_id, current_version_no, version)
            VALUES (#{runId}, #{taskId}, 0, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(ContentItemEntity item);

    @Select("SELECT * FROM content_item WHERE run_id = #{runId} AND task_id = #{taskId}")
    ContentItemEntity findItemByRunAndTask(@Param("runId") long runId,
                                           @Param("taskId") String taskId);

    @Select("SELECT * FROM content_item WHERE id = #{id}")
    ContentItemEntity findItemById(long id);

    @Select("SELECT * FROM content_item WHERE run_id = #{runId} ORDER BY id")
    List<ContentItemEntity> findItemsByRunId(long runId);

    @Insert("""
            INSERT INTO content_version
                (content_item_id, version_no, content_json, source_refs_json, origin,
                 source_artifact_id, source_artifact_version, source_artifact_status,
                 created_by)
            VALUES
                (#{contentItemId}, #{versionNo}, #{contentJson}, #{sourceRefsJson}, #{origin},
                 #{sourceArtifactId}, #{sourceArtifactVersion}, #{sourceArtifactStatus},
                 #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertVersion(ContentVersionEntity version);

    @Select("SELECT * FROM content_version WHERE source_artifact_id = #{artifactId}")
    ContentVersionEntity findVersionByArtifactId(String artifactId);

    @Select("SELECT * FROM content_version WHERE id = #{id}")
    ContentVersionEntity findVersionById(long id);

    @Select("""
            SELECT * FROM content_version
            WHERE content_item_id = #{contentItemId}
            ORDER BY version_no, id
            """)
    List<ContentVersionEntity> findVersionsByItemId(long contentItemId);

    @Update("""
            UPDATE content_item
            SET current_version_no = #{versionNo}, version = version + 1
            WHERE id = #{itemId} AND current_version_no < #{versionNo}
            """)
    int advanceAgentVersion(@Param("itemId") long itemId,
                            @Param("versionNo") int versionNo);

    @Update("""
            UPDATE content_item
            SET current_version_no = #{newVersionNo}, version = version + 1
            WHERE id = #{itemId}
              AND current_version_no = #{expectedCurrentVersionNo}
              AND version = #{expectedItemVersion}
            """)
    int appendVersionCas(@Param("itemId") long itemId,
                         @Param("expectedCurrentVersionNo") int expectedCurrentVersionNo,
                         @Param("expectedItemVersion") long expectedItemVersion,
                         @Param("newVersionNo") int newVersionNo);

    @Update("""
            UPDATE content_item item
            JOIN content_version content_version
              ON content_version.content_item_id = item.id
             AND content_version.id = #{contentVersionId}
            SET item.version = item.version + 1
            WHERE item.id = #{itemId}
              AND item.current_version_no = #{expectedCurrentVersionNo}
              AND item.version = #{expectedItemVersion}
              AND content_version.version_no = item.current_version_no
              AND (content_version.source_artifact_status IS NULL
                   OR content_version.source_artifact_status <> 'INVALIDATED')
              AND NOT EXISTS (
                    SELECT 1 FROM approval_record approval
                    WHERE approval.content_version_id = content_version.id)
            """)
    int reserveApprovalCas(@Param("itemId") long itemId,
                           @Param("contentVersionId") long contentVersionId,
                           @Param("expectedCurrentVersionNo") int expectedCurrentVersionNo,
                           @Param("expectedItemVersion") long expectedItemVersion);

    @Insert("""
            INSERT INTO approval_record(content_version_id, actor_id, comment_text)
            VALUES (#{contentVersionId}, #{actorId}, #{commentText})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertApproval(ApprovalRecordEntity approval);

    @Select("SELECT * FROM approval_record WHERE id = #{id}")
    ApprovalRecordEntity findApprovalById(long id);

    @Select("""
            SELECT * FROM approval_record
            WHERE content_version_id IN (
                SELECT id FROM content_version WHERE content_item_id = #{contentItemId})
            ORDER BY created_at, id
            """)
    List<ApprovalRecordEntity> findApprovalsByItemId(long contentItemId);

    @Insert("""
            INSERT INTO review_report
                (run_id, source_artifact_id, source_artifact_version,
                 source_artifact_status, passed, repair_round)
            VALUES
                (#{runId}, #{sourceArtifactId}, #{sourceArtifactVersion},
                 #{sourceArtifactStatus}, #{passed}, #{repairRound})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReview(ReviewReportEntity review);

    @Select("SELECT * FROM review_report WHERE source_artifact_id = #{artifactId}")
    ReviewReportEntity findReviewByArtifactId(String artifactId);

    @Select("SELECT * FROM review_report WHERE run_id = #{runId} ORDER BY created_at, id")
    List<ReviewReportEntity> findReviewsByRunId(long runId);

    @Insert("""
            INSERT INTO review_issue
                (review_report_id, issue_index, issue_type, affected_task_id, message)
            VALUES
                (#{reviewReportId}, #{issueIndex}, #{issueType}, #{affectedTaskId}, #{message})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIssue(ReviewIssueEntity issue);

    @Select("""
            SELECT * FROM review_issue
            WHERE review_report_id = #{reportId}
            ORDER BY issue_index, id
            """)
    List<ReviewIssueEntity> findIssuesByReportId(long reportId);
}
