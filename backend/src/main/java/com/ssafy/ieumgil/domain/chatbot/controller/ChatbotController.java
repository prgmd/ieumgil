package com.ssafy.ieumgil.domain.chatbot.controller;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/chatbot")
@Tag(name = "챗봇 Controller")
public class ChatbotController {

    private final ChatbotCommandService chatbotCommandService;

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PostMapping("/messages")
    @Operation(summary = "챗봇 메시지 전송", description = "사용자 메시지를 GMS(Anthropic Claude)로 전달하고 응답을 받습니다. 프로젝트+멤버 단위로 최근 대화 히스토리가 유지됩니다.")
    public ResponseEntity<CustomResponse<ChatbotResDTO.MessageResult>> sendMessage(
            @PathVariable Long projectId,
            @AuthenticationPrincipal Long memberId,
            @RequestBody @Valid ChatbotReqDTO.SendMessage request
    ) {
        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(projectId, memberId, request);
        return ResponseEntity.ok(CustomResponse.onSuccess(result));
    }
}
