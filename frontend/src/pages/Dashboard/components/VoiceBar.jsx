// pages/Dashboard/components/VoiceBar.jsx
//
// 보이스 위젯 — 화면 맨 아래 가장자리에 붙은 탭(Vue DevTools 의 그 탭처럼).
// 평소엔 윗부분만 빼꼼 보이다가 올리면 다 나오고, 누르면 그 위로 마이크·
// 스피커 아이콘이 펼쳐진다. 입장하면 자동 연결(권한 거부 시 듣기 전용)이고,
// 버튼은 송신(마이크)·수신(스피커)만 끄고 켠다 — 접어 둬도 연결은
// 대시보드를 떠날 때까지 유지된다.

export function VoiceBar({ voice, voiceOpen, setVoiceOpen, currentUser, boardMembers }) {
  return (
    <div className={`voice-bar ${voiceOpen ? "is-open" : ""}`}>
      {voiceOpen && (
        <div className="voice-items" role="group" aria-label="음성 채팅 컨트롤">
          <button
            type="button"
            className={`voice-mic ${voice.micOn && !voice.listenOnly ? "on" : "off"}`}
            onClick={voice.toggleMic}
            disabled={voice.listenOnly}
            title={
              voice.listenOnly
                ? "마이크 권한이 거부되어 듣기만 가능해요"
                : voice.micOn
                  ? "마이크 끄기"
                  : "마이크 켜기"
            }
          >
            {voice.listenOnly ? "🎧" : voice.micOn ? "🎤" : "🔇"}
          </button>
          {/* 전체 음소거 ↔ 전체 듣기 — 상대 소리만 끈다(내 목소리는 계속 나감) */}
          <button
            type="button"
            className={`voice-mic ${voice.speakerOn ? "on" : "off"}`}
            onClick={voice.toggleSpeaker}
            title={
              voice.speakerOn
                ? "전체 음소거 — 모두의 소리 끄기"
                : "전체 듣기 — 다시 듣기"
            }
          >
            {voice.speakerOn ? "🔊" : "🔈"}
          </button>
          <span className="voice-status">
            {/* 인원은 나를 포함해 센다 — 나+A+B 면 3명 */}
            {!voice.joined
              ? "음성 연결 중..."
              : voice.listenOnly
                ? `듣기 전용 · ${voice.connectedCount + 1}명`
                : voice.connectedCount > 0
                  ? `음성 연결됨 · ${voice.connectedCount + 1}명`
                  : "혼자 있어요"}
          </span>
          {/* 참여자 아바타 (QA 배치3) — 나 + 음성 연결이 수립된 멤버들 */}
          {voice.joined && (
            <span className="voice-peers">
              {[currentUser?.id, ...voice.connectedIds]
                .filter((id) => id != null)
                .map((id) => {
                  const isMe = id === currentUser?.id;
                  const member = isMe
                    ? {
                        nickname: currentUser?.nickname ?? "나",
                        profileImg: currentUser?.profileImg,
                      }
                    : boardMembers.find((m) => m.memberId === id);
                  if (!member) return null;
                  // 말하는 중이면 링 — 내 것은 memberId 가 아니라 selfSpeaking 이다
                  const speaking = isMe
                    ? voice.selfSpeaking
                    : voice.speakingIds?.has(id);
                  return (
                    <i
                      key={id}
                      className={`voice-peer ${speaking ? "is-speaking" : ""}`}
                      title={`${member.nickname}${isMe ? " (나)" : ""}${
                        speaking ? " · 말하는 중" : ""
                      }`}
                    >
                      {member.profileImg?.startsWith("http") ? (
                        <img src={member.profileImg} alt="" />
                      ) : (
                        (member.nickname?.[0] ?? "?")
                      )}
                    </i>
                  );
                })}
            </span>
          )}
        </div>
      )}

      {/* 하단 탭 — 접힘/펼침만 한다. 마이크를 토글하지 않는다(접힌 채로 잘못
          눌러 목소리가 나가는 사고 방지). 접었을 때는 마이크 상태와 인원수를
          여기서 읽는다 — 아이콘이 사라져도 상태는 알아야 한다. */}
      <button
        type="button"
        // 접어 둬도 "지금 누가 말한다"는 건 보여야 한다 — 탭 테두리가 링 역할
        className={`voice-tab ${
          voice.selfSpeaking || voice.speakingIds?.size > 0 ? "is-speaking" : ""
        }`}
        onClick={() => setVoiceOpen((open) => !open)}
        aria-expanded={voiceOpen}
        title={voiceOpen ? "음성 컨트롤 접기" : "음성 컨트롤 펼치기"}
        aria-label={voiceOpen ? "음성 컨트롤 접기" : "음성 컨트롤 펼치기"}
      >
        <span>{voice.listenOnly ? "🎧" : voice.micOn ? "🎤" : "🔇"}</span>
        {voice.joined && <span>{voice.connectedCount + 1}</span>}
        <span className="voice-tab-caret" aria-hidden="true">
          {voiceOpen ? "▼" : "▲"}
        </span>
      </button>
    </div>
  );
}
