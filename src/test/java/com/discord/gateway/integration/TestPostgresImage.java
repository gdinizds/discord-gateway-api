package com.discord.gateway.integration;

import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

final class TestPostgresImage {

    static final DockerImageName IMAGE = DockerImageName.parse(
            new ImageFromDockerfile()
                    .withDockerfile(Paths.get("docker/postgres/Dockerfile"))
                    .get()
    ).asCompatibleSubstituteFor(PostgreSQLContainer.IMAGE);

    private TestPostgresImage() {
    }
}
