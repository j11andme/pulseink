package com.pulseink.repository.feedback;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeedbackMapper {

    @Insert("""
            INSERT INTO feedback_inbox
                (event_id, publication_id, schema_version,
                 source_topic, source_partition, source_offset)
            VALUES
                (#{eventId}, #{publicationId}, #{schemaVersion},
                 #{sourceTopic}, #{sourcePartition}, #{sourceOffset})
            """)
    int insertInbox(@Param("eventId") String eventId,
                    @Param("publicationId") long publicationId,
                    @Param("schemaVersion") int schemaVersion,
                    @Param("sourceTopic") String sourceTopic,
                    @Param("sourcePartition") int sourcePartition,
                    @Param("sourceOffset") long sourceOffset);

    @Insert("""
            INSERT INTO content_metric_daily
                (publication_id, metric_date, views, clicks, likes)
            VALUES
                (#{publicationId}, #{metricDate}, #{views}, #{clicks}, #{likes})
            ON DUPLICATE KEY UPDATE
                views = views + #{views},
                clicks = clicks + #{clicks},
                likes = likes + #{likes}
            """)
    int upsertMetric(@Param("publicationId") long publicationId,
                     @Param("metricDate") LocalDate metricDate,
                     @Param("views") long views,
                     @Param("clicks") long clicks,
                     @Param("likes") long likes);

    @Select("""
            SELECT publication_id, metric_date, views, clicks, likes
            FROM content_metric_daily metric
            JOIN publication pub ON pub.id = metric.publication_id
            WHERE pub.run_id = #{runId}
            ORDER BY metric.publication_id, metric.metric_date
            """)
    List<ContentMetricDailyEntity> findMetricsByRunId(long runId);

    @Select("SELECT id, run_id FROM publication WHERE id = #{publicationId}")
    FeedbackRepositoryPublicationRow selectPublicationRow(long publicationId);
}
