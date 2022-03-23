package com.xxuz.piclane.foltiaapi.dao

import com.xxuz.piclane.foltiaapi.model.vo.SubtitleQueryInput
import com.xxuz.piclane.foltiaapi.model.Subtitle
import com.xxuz.piclane.foltiaapi.model.VideoType
import com.xxuz.piclane.foltiaapi.model.vo.SubtitleUpdateInput
import com.xxuz.piclane.foltiaapi.model.vo.SubtitleResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * 放送Dao
 */
@Repository
class SubtitleDao(
        @Autowired
        private val jt: NamedParameterJdbcTemplate,

        @Autowired
        private val cacheMgr: CacheManager,
) {
    companion object {
        private const val defaultPageRows = 100
    }

    /**
     * ID から放送を取得します
     */
    @Cacheable(cacheNames = ["subtitle"], key = "'pid=' + #pId")
    fun get(pId: Long): Subtitle? =
        try {
            jt.queryForObject(
                """
                SELECT
                    *
                FROM
                    foltia_subtitle
                WHERE
                    pid = :pId
                """,
                mutableMapOf(
                    "pId" to pId
                ),
                RowMapperImpl
            )
        } catch (e: EmptyResultDataAccessException) {
            null
        }

    /**
     * 放送を検索します
     *
     * @param query クエリ
     * @param page ページインデックス
     * @param pageRows ページあたりの行数
     */
    fun find(query: SubtitleQueryInput?, page: Int, pageRows: Int = defaultPageRows): SubtitleResult {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>(
            "limit" to pageRows,
            "offset" to pageRows * page
        )

        if(query?.tId != null) {
            conditions.add("S.tid = :tId")
            params["tId"] = query.tId
        }
        if(query?.recordingType != null) {
            when(query.recordingType) {
                Subtitle.RecordingType.Program -> conditions.add("S.tid > 0")
                Subtitle.RecordingType.Epg -> conditions.add("S.tid = 0")
                Subtitle.RecordingType.Keyword -> conditions.add("S.tid = -1")
            }
        }
        if(query?.receivableStation != null) {
            conditions.add("ST.receiving = :receivableStation")
            params["receivableStation"] = if(query.receivableStation) 1 else 0
        }
        if(query?.hasRecording == true) {
            conditions.add("""(
                (S.m2pfilename IS NOT NULL AND EXISTS(SELECT 1 FROM foltia_m2pfiles AS TS WHERE TS.m2pfilename = S.m2pfilename)) OR 
                (S.pspfilename IS NOT NULL AND EXISTS(SELECT 1 FROM foltia_mp4files AS SD WHERE SD.mp4filename = S.pspfilename)) OR 
                (S.mp4hd IS NOT NULL AND EXISTS(SELECT 1 FROM foltia_hdmp4files AS HD WHERE HD.hdmp4filename = S.mp4hd))
            )""".trimIndent())
        } else if(query?.hasRecording == false) {
            conditions.add("(S.m2pfilename IS NULL AND S.pspfilename IS NULL AND S.mp4hd IS NULL)")
        }
        if(query?.keyword != null) {
            conditions.add("S.subtitle LIKE :keyword")
            conditions.add("P.title LIKE :keyword")
            conditions.add("P.shorttitle LIKE :keyword")
            conditions.add("P.titleyomi LIKE :keyword")
            conditions.add("P.titleen LIKE :keyword")
            params["keyword"] = "%${query.keyword}%"
        }

        val where = if(conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val data =  jt.query(
            """
            SELECT
                *
            FROM
                foltia_subtitle AS S
            INNER JOIN
                foltia_program AS P ON S.tid = P.tid
            INNER JOIN
                foltia_station AS ST ON S.stationid = ST.stationid
            $where
            ORDER BY startdatetime DESC
            LIMIT :limit
            OFFSET :offset
            """,
            params,
            RowMapperImpl
        )
        val total = jt.queryForObject(
            """
            SELECT 
                COUNT(*)
            FROM
                foltia_subtitle AS S
            INNER JOIN
                foltia_program AS P ON S.tid = P.tid
            INNER JOIN
                foltia_station AS ST ON S.stationid = ST.stationid
            $where
            """,
            params,
            Int::class.java
        )

        return SubtitleResult(page, total ?: 0, data)
    }

    /**
     * 放送を更新します
     *
     * @param input 更新入力
     */
    fun update(input: SubtitleUpdateInput) {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>(
            "pId" to input.pId
        )

        if(input.subtitleDefined && input.subtitle?.isNotBlank() == true) {
            sets.add("subtitle = :subtitle")
            params["subtitle"] = input.subtitle
        }
        if(input.fileStatusDefined && input.fileStatus != null) {
            sets.add("filestatus = :fileStatus")
            params["fileStatus"] = input.fileStatus
        }
        if(input.encodeSettingDefined && input.encodeSetting != null) {
            sets.add("encodesetting = :encodeSetting")
            params["encodeSetting"] = input.encodeSetting
        }

        jt.update(
            """
            UPDATE
                foltia_subtitle
            SET
                ${sets.joinToString(", ")}
            WHERE
                pid = :pId
            """,
            params
        )
    }

    /**
     * 動画ファイルを更新します
     *
     * @param pId 放送ID
     * @param videoType 動画ファイルの種別
     * @param filenameProvider ファイル名プロバイダ
     */
    fun updateVideo(pId: Long, videoType: VideoType, filenameProvider: (subtitle: Subtitle, videoType: VideoType) -> String): Subtitle? {
        val subtitle = get(pId) ?: return null
        val filename = filenameProvider(subtitle, videoType)

        // foltia_subtitle の列を更新
        val videoColumn = videoColumn(videoType)
        jt.update(
            """
            UPDATE foltia_subtitle SET $videoColumn = :filename WHERE pid = :pId
            """,
            mapOf(
                "pId" to pId,
                "filename" to filename
            )
        )

        // 各種動画ファイルテーブルの行を追加
        val videoTable = videoTable(videoType)
        if(videoType == VideoType.TS) {
            jt.update(
                """
                INSERT INTO ${videoTable.first}(${videoTable.second}) VALUES (:filename) ON CONFLICT DO NOTHING
                """,
                mapOf(
                    "filename" to filename
                )
            )
        } else {
            jt.update(
                """
                INSERT INTO ${videoTable.first}(tid, ${videoTable.second}) VALUES (:tId, :filename) ON CONFLICT DO NOTHING
                """,
                mapOf(
                    "tId" to subtitle.tId,
                    "filename" to filename
                )
            )
        }

        // キャッシュの削除
        cacheMgr.getCache("subtitle")?.evictIfPresent("pId = $pId")

        return get(pId)
    }

    /**
     * 動画ファイルを削除します
     *
     * @param pId 放送ID
     * @param videoTypes 動画ファイルの種別
     */
    fun deleteVideo(pId: Long, videoTypes: Set<VideoType>): Pair<Subtitle, Subtitle>? {
        val subtitle = get(pId) ?: return null

        // foltia_subtitle の列を更新
        val subtitleSet = videoTypes.map {
            "${videoColumn(it)} = null"
        }
        jt.update(
            """
            UPDATE foltia_subtitle SET ${subtitleSet.joinToString(", ")} WHERE pid = :pId
            """,
            mapOf(
                "pId" to pId
            )
        )

        // 各種動画ファイルテーブルの行を削除
        videoTypes.forEach { videoType ->
            val filename = subtitle.videoFilename(videoType) ?: return@forEach
            val videoTable = videoTable(videoType)
            jt.update(
                """
                DELETE FROM ${videoTable.first} WHERE ${videoTable.second} = :filename
                """,
                mapOf(
                    "filename" to filename
                )
            )
        }

        // キャッシュの削除
        cacheMgr.getCache("subtitle")?.evictIfPresent("pId = $pId")

        return subtitle to (get(pId) ?: throw RuntimeException())
    }

    /**
     * 動画ファイルの種別から foltia_subtitle の列名を取得します
     */
    private fun videoColumn(videoType: VideoType) =
        when(videoType) {
            VideoType.TS -> "m2pfilename"
            VideoType.SD -> "pspfilename"
            VideoType.HD -> "mp4hd"
        }

    /**
     * 動画ファイルの種別から動画ファイルテーブルの表名と列名を取得します
     */
    private fun videoTable(videoType: VideoType) =
        when(videoType) {
            VideoType.TS -> "foltia_m2pfiles" to "m2pfilename"
            VideoType.SD -> "foltia_mp4files" to "mp4filename"
            VideoType.HD -> "foltia_hdmp4files" to "hdmp4filename"
        }

    /**
     * ResultSet から Subtitle にマッピングする RowMapper
     */
    private object RowMapperImpl : RowMapper<Subtitle> {
        override fun mapRow(rs: ResultSet, rowNum: Int): Subtitle =
            Subtitle(
                pId = rs.getLong("pid"),
                tId = rs.getLong("tid"),
                stationId = rs.getLong("stationid"),
                countNo = rs.getLong("countno").let { if (rs.wasNull()) null else it },
                subtitle = rs.getString("subtitle"),
                startDateTime = rs.getLong("startdatetime").toLocalDateTime(),
                endDateTime = rs.getLong("enddatetime").toLocalDateTime(),
                startOffset = rs.getLong("startoffset"),
                lengthMin = rs.getLong("lengthmin"),
                m2pFilename = rs.getString("m2pfilename"),
                pspFilename = rs.getString("pspfilename"),
                epgAddedBy = rs.getLong("epgaddedby").let { if (rs.wasNull()) null else it },
                lastUpdate = rs.getTimestamp("lastupdate").toOffsetDateTime().orElse(null),
                fileStatus = rs.getInt("filestatus").let {
                    if (rs.wasNull())
                        null
                    else
                        Subtitle.FileStatus.codeOf(it).orElseThrow()
                },
                aspect = rs.getInt("aspect").let { if (rs.wasNull()) null else it },
                encodeSetting = rs.getInt("encodesetting").let {
                    if (rs.wasNull())
                        null
                    else
                        Subtitle.TranscodeQuality.codeOf(it).orElseThrow()
                },
                mp4hd = rs.getString("mp4hd"),
                syobocalFlag = rs.getInt("syobocalflag").let {
                    if (rs.wasNull())
                        emptySet()
                    else
                        Subtitle.SyobocalFlag.codesOf(it)
                },
                syobocalRev = rs.getInt("syobocalrev"),
            )
    }
}