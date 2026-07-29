import './shared/styles/index.css';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../global/stores/authStore';
import { useMyGroups } from '../../features/my/hooks/useMyGroups';
import { useToastStore } from '../../global/stores/toastStore';
import CreateGroupModal from './components/CreateGroupModal';
import DeleteGroupModal from './components/DeleteGroupModal';
import { AppBar } from './shared/ui/AppBar';

export function MyPage() {
  const currentUser = useAuthStore((s) => s.currentUser);
  // 그룹 목록은 이 페이지가 소유한다 — 훅이 조회·갱신을 함께 담당한다.
  const { groups, status, createGroup, renameGroup, deleteGroup, joinByCode } =
    useMyGroups(currentUser?.id);
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
      setCodeErr(messageFor(e.code));
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
                  <div className="meta">
                    멤버 {g.members.length}명 · 완료 여행 {g.doneTrips}개
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
              친구에게 받은 8자리 코드를 입력하세요.
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
            <p style={{ fontSize: 11, color: 'var(--ink2)', marginTop: 8 }}>
              테스트용 — <code>EXPIRED1</code>(만료), <code>FULL0009</code>(정원초과),{' '}
              <code>YJ3K7Q2M</code>(이미 가입), <code>ZZZZZZZZ</code>(존재하지 않음)
            </p>
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

// MY-05: 실패 사유 4종을 사용자 문구로 매핑
function messageFor(code) {
  switch (code) {
    case 'INVALID_FORMAT':
      return '코드는 영대문자·숫자 8자리입니다.';
    case 'CODE_NOT_FOUND':
      return '존재하지 않는 코드예요. 코드를 다시 확인해주세요.';
    case 'CODE_EXPIRED':
      return '만료된 코드입니다 — 그룹 멤버에게 재발급을 요청하세요.';
    case 'GROUP_FULL':
      return '정원이 가득 찼어요 (최대 10명).';
    case 'ALREADY_MEMBER':
      return '이미 가입된 그룹이에요.';
    default:
      return '입장에 실패했어요. 잠시 후 다시 시도해주세요.';
  }
}
