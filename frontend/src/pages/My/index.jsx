import './shared/styles/index.css';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../global/stores/authStore';
import { useMyGroups } from '../../features/my/hooks/useMyGroups';
import { useToastStore } from '../../global/stores/toastStore';
import { ERROR_CODE } from '../../global/api/errorCodes';
import CreateGroupModal from './components/CreateGroupModal';
import DeleteGroupModal from './components/DeleteGroupModal';
import { AppBar } from './shared/ui/AppBar';

export function MyPage() {
  const currentUser = useAuthStore((s) => s.currentUser);
  // 그룹 목록은 이 페이지가 소유한다 — 훅이 조회·갱신을 함께 담당한다.
  const { groups, status, createGroup, renameGroup, deleteGroup, joinByCode } =
    useMyGroups(currentUser);
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();

  const [code, setCode] = useState('');
  const [codeErr, setCodeErr] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null); // 삭제 모달에 넘길 그룹
  const [editingId, setEditingId] = useState(null); // 인라인 이름 수정 중인 그룹 id
  const [editValue, setEditValue] = useState('');

  async function handleJoin() {
    setCodeErr('');
    try {
      const group = await joinByCode(code);
      setCode('');
      navigate(`/groups/${group.id}`);
    } catch (e) {
      setCodeErr(messageFor(e));
    }
  }

  function startEdit(g) {
    setEditingId(g.id);
    setEditValue(g.name);
  }

  async function commitEdit(groupId) {
    const trimmed = editValue.trim();
    setEditingId(null);
    if (!trimmed) return; // 빈 값이면 조용히 취소 (원본 이름 유지)
    try {
      await renameGroup(groupId, trimmed);
      showToast('이름이 수정됐어요 ✓');
    } catch {
      showToast('그룹명은 2~20자로 입력해주세요.');
    }
  }

  return (
    <>
      <AppBar crumbs={[{ label: '개인 페이지' }]} />
      <div className="page">
      <div className="sec-head">
        <h2>내 그룹</h2>
        <span>카드를 눌러 입장</span>
      </div>

      {/* group-grid 재사용: 좌측 콘텐츠 + 우측 320px 고정 패널.
          sec-head를 grid 밖으로 빼서 양쪽 칸이 실제 콘텐츠(카드/패널)로
          동시에 시작하게 하고, 오른쪽 칸은 스크롤 시 sticky로 고정된다. */}
      <div className="group-grid">
        <div>
          <div className="grid-groups">
            {status === 'loading' && <p>불러오는 중…</p>}
            {status === 'error' && <p>그룹 목록을 불러오지 못했어요.</p>}
            {groups.map((g) => {
              const isEditing = editingId === g.id;
              return (
                <div
                  key={g.id}
                  className="g-card"
                  onClick={() => !isEditing && navigate(`/groups/${g.id}`)}
                >
                  {isEditing ? (
                    <input
                      className="rename-input"
                      value={editValue}
                      autoFocus
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => setEditValue(e.target.value)}
                      onBlur={() => commitEdit(g.id)}
                      onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                      maxLength={20}
                      style={{
                        fontSize: 16,
                        fontWeight: 800,
                        border: '1.5px solid var(--acc)',
                        borderRadius: 8,
                        padding: '2px 8px',
                        width: '90%',
                      }}
                    />
                  ) : (
                    <h3>{g.name}</h3>
                  )}
                  {/* memberCount·tripCount 는 서버가 계산해 내려준다(GroupResDTO.Summary).
                      tripCount 는 완료 여부와 무관한 전체 프로젝트 수라 "완료"를 붙이지 않는다. */}
                  <div className="meta">
                    멤버 {g.memberCount}명 · 여행 {g.tripCount}개
                  </div>
                  {/* flat 모델 — 모든 멤버가 이름 수정·그룹 삭제를 할 수 있다 */}
                  {!isEditing && (
                    <div className="ops">
                      <button
                        className="op"
                        onClick={(e) => {
                          e.stopPropagation();
                          startEdit(g);
                        }}
                      >
                        ✎
                      </button>
                      <button
                        className="op"
                        onClick={(e) => {
                          e.stopPropagation();
                          setDeleteTarget(g);
                        }}
                      >
                        🗑
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
            <div className="g-card add" onClick={() => setCreateOpen(true)}>
              ＋ 새 그룹 만들기
            </div>
          </div>
        </div>

        <div>
          <div className="pnl">
            <h3>초대 코드로 입장</h3>
            <p style={{ fontSize: 12.5, color: 'var(--ink2)' }}>
              친구에게 받은 8자리 코드를 입력하세요. 코드는 발급 후 7일간 유효합니다.
            </p>
            <div className="code-input">
              <input
                maxLength={8}
                placeholder="예: YJ3K7Q2M"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                onKeyDown={(e) => e.key === 'Enter' && handleJoin()}
              />
              <button className="btn btn-acc" onClick={handleJoin}>
                입장
              </button>
            </div>
            {codeErr && <div className="code-err">{codeErr}</div>}
          </div>
        </div>
      </div>

      <CreateGroupModal
        open={createOpen}
        onCreate={createGroup}
        onClose={() => setCreateOpen(false)}
      />
      <DeleteGroupModal
        open={!!deleteTarget}
        group={deleteTarget}
        onDelete={deleteGroup}
        onClose={() => setDeleteTarget(null)}
      />
      </div>
    </>
  );
}

// MY-05: 입장 실패 사유를 사용자 문구로 매핑.
//
// 여기서 직접 적는 건 서버 문구보다 부드럽게 쓰고 싶은 다섯 가지뿐이고, 그 밖의
// 코드는 서버가 준 message 를 그대로 보여준다 — 고정 문구로 뭉개면 "잠시 후 다시
// 시도"만 반복돼 실제 사유를 알 수 없다.
//
// VALIDATION_FAILED 는 공용 코드라 화면마다 의미가 다르지만, 이 폼에서 오는 400 은
// 초대 코드 형식 위반뿐이다(@Pattern ^[A-Z0-9]{8}$).
function messageFor(error) {
  switch (error?.code) {
    case ERROR_CODE.VALIDATION_FAILED:
      return '코드는 영대문자·숫자 8자리입니다.';
    case ERROR_CODE.INVITE_CODE_NOT_FOUND:
      return '존재하지 않는 코드예요. 코드를 다시 확인해주세요.';
    case ERROR_CODE.INVITE_CODE_EXPIRED:
      return '만료된 코드입니다 — 그룹 멤버에게 재발급을 요청하세요.';
    case ERROR_CODE.GROUP_FULL:
      return '정원이 가득 찼어요 (최대 10명).';
    case ERROR_CODE.ALREADY_GROUP_MEMBER:
      return '이미 가입된 그룹이에요.';
    default:
      return error?.message ?? '입장에 실패했어요. 잠시 후 다시 시도해주세요.';
  }
}
