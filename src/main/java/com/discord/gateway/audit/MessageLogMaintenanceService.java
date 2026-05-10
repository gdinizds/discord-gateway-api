package com.discord.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@Profile("!test & !local")
public class MessageLogMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MessageLogMaintenanceService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    private final NamedParameterJdbcTemplate jdbc;

    public MessageLogMaintenanceService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 50 23 * * *")
    public void createTomorrowPartitions() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        String suffix = tomorrow.format(FMT);
        String from = tomorrow.toString();
        String to = tomorrow.plusDays(1).toString();
        String parentName = "gateway.message_log_" + suffix;

        String createParent = """
                CREATE TABLE IF NOT EXISTS %s
                    PARTITION OF gateway.message_log
                    FOR VALUES FROM ('%s') TO ('%s')
                    PARTITION BY HASH (guild_id)
                """.formatted(parentName, from, to);

        try {
            jdbc.update(createParent, new MapSqlParameterSource());
            for (int i = 0; i < 8; i++) {
                String subName = parentName + "_h" + i;
                String createSub = """
                        CREATE TABLE IF NOT EXISTS %s
                            PARTITION OF %s
                            FOR VALUES WITH (MODULUS 8, REMAINDER %d)
                        """.formatted(subName, parentName, i);
                jdbc.update(createSub, new MapSqlParameterSource());
            }
            log.info("Partições criadas para message_log [date={}]", tomorrow);
        } catch (Exception e) {
            log.error("Falha ao criar partições message_log [date={}]", tomorrow, e);
        }
    }

    @Scheduled(cron = "0 55 23 * * *")
    public void dropOldPartitions() {
        LocalDate dropDay = LocalDate.now().minusDays(8);
        String suffix = dropDay.format(FMT);
        String partitionName = "gateway.message_log_" + suffix;

        try {
            jdbc.update("DROP TABLE IF EXISTS " + partitionName + " CASCADE",
                    new MapSqlParameterSource());
            log.info("Partição antiga removida [partition={}]", partitionName);
        } catch (Exception e) {
            log.error("Falha ao remover partição antiga [partition={}]", partitionName, e);
        }
    }
}
