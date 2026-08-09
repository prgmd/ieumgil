package com.ssafy.ieumgil.domain.chatbot;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
class GmsConnectionTest {

	@Test
	void gmsRespondsThroughSpringAi() throws IOException {
		String apiKey = readGmsApiKeyFromDotenv();
		Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

		AnthropicApi anthropicApi = AnthropicApi.builder()
				.baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
				.apiKey(apiKey)
				.build();

		AnthropicChatModel chatModel = AnthropicChatModel.builder()
				.anthropicApi(anthropicApi)
				.defaultOptions(AnthropicChatOptions.builder()
						.model("claude-haiku-4-5-20251001")
						.maxTokens(1024)
						.build())
				.build();

		ChatClient chatClient = ChatClient.create(chatModel);

		String response = chatClient.prompt()
				.user("한 단어로만 답해줘: 이음길")
				.call()
				.content();

		assertThat(response).isNotBlank();
	}

	private String readGmsApiKeyFromDotenv() throws IOException {
		Path envFile = Path.of(".env");
		if (!Files.exists(envFile)) {
			return null;
		}
		Properties props = new Properties();
		try (var reader = Files.newBufferedReader(envFile)) {
			props.load(reader);
		}
		return props.getProperty("GMS_API_KEY");
	}
}
