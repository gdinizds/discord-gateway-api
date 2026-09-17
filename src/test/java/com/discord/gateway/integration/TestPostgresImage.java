package com.discord.gateway.integration;

import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

final class TestPostgresImage {

    private static final DockerImageName IMAGE = DockerImageName.parse(
            new ImageFromDockerfile()
                    .withDockerfile(Paths.get("docker/postgres/Dockerfile"))
                    .get()
    ).asCompatibleSubstituteFor(PostgreSQLContainer.IMAGE);

    static PostgreSQLContainer newContainer(String databaseName) {
        return new PostgreSQLContainer(IMAGE)
                .withCommand("postgres",
                        "-c", "shared_preload_libraries=pg_cron",
                        "-c", "cron.database_name=" + databaseName)
                .withDatabaseName(databaseName);
    }

    private TestPostgresImage() {
    }
}
