package com.discord.gateway.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaProducerConfig {

    @Bean NewTopic topicMessageCreated()     { return TopicBuilder.name("discord.events.message.created").build(); }
    @Bean NewTopic topicMessageUpdated()     { return TopicBuilder.name("discord.events.message.updated").build(); }
    @Bean NewTopic topicInteractionCommand() { return TopicBuilder.name("discord.events.interaction.command").build(); }
    @Bean NewTopic topicInteractionButton()  { return TopicBuilder.name("discord.events.interaction.button").build(); }
    @Bean NewTopic topicInteractionModal()   { return TopicBuilder.name("discord.events.interaction.modal").build(); }
    @Bean NewTopic topicGuildMember()        { return TopicBuilder.name("discord.events.guild.member").build(); }
    @Bean NewTopic topicGuildUpdated()       { return TopicBuilder.name("discord.events.guild.updated").build(); }
}
