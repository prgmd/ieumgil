package com.ssafy.ieumgil.domain.chatbot.controller;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotQueryService;
import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ChatbotQueryService chatbotQueryService;

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PostMapping("/messages")
    @Operation(summary = "챗봇 메시지 전송", description = "사용자 메시지를 GMS(Anthropic Claude)로 전달하고 응답을 받습니다. 프로젝트+멤버 단위로 최근 대화 히스토리가 유지됩니다.")
    public CustomResponse<ChatbotResDTO.MessageResult> sendMessage(
            @PathVariable Long projectId,
            @AuthenticationPrincipal Long memberId,
            @RequestBody @Valid ChatbotReqDTO.SendMessage request
    ) {
        return CustomResponse.onSuccess(chatbotCommandService.sendMessage(projectId, memberId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @GetMapping("/messages")
    @Operation(summary = "챗봇 대화 이력 조회", description = "프로젝트+멤버 단위로 저장된 최근 대화(추천 후보 포함)를 반환합니다.")
    public CustomResponse<ChatbotResDTO.HistoryResult> getHistory(
            @PathVariable Long projectId,
            @AuthenticationPrincipal Long memberId
    ) {
        return CustomResponse.onSuccess(chatbotQueryService.loadHistory(projectId, memberId));
    }
}
