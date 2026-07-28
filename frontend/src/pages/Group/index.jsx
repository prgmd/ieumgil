import '../My/shared/styles/index.css';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useGroupStore } from '../My/shared/stores/groupStore';
import { useToastStore } from '../My/shared/stores/toastStore';
import LeaveGroupModal from './components/LeaveGroupModal';
import CreateProjectModal from './components/CreateProjectModal';
import DeleteProjectModal from './components/DeleteProjectModal';

// PROJECT.transport_pref — ERD상 CAR | PUBLIC 두 값만 저장하고 표기만 한글로 한다.
const TRANSPORT_LABEL = { CAR: '자차', PUBLIC: '대중교통' };

export function GroupPage() {
  // 라우트 파라미터는 문자열 — 목업/서버의 숫자 ID와 맞추려면 변환이 필요하다.
  const groupId = Number(useParams().groupId);
  const { currentGroup, projects, loadGroup, loadProjects, reissueInviteCode } = useGroupStore();
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();

  const [leaveOpen, setLeaveOpen] = useState(false);
  const [createProjectOpen, setCreateProjectOpen] = useState(false);
  const [deleteProjectTarget, setDeleteProjectTarget] = useState(null);

  async function handleCopyCode() {
    try {
      await navigator.clipboard.writeText(currentGroup.inviteCode);
      showToast('초대 코드가 복사됐어요 🔗');
    } catch {
      showToast('복사에 실패했어요 — 코드를 직접 선택해 복사해주세요.');
    }
  }

  async function handleReissue() {
    await reissueInviteCode(groupId);
    showToast('코드 재발급 — 기존 코드는 즉시 무효화됐어요');
  }

  // groupId만 들고 들어오므로 그룹·프로젝트를 여기서 직접 조회한다.
  // 없는 그룹이면 개인 페이지로 되돌린다.
  useEffect(() => {
    loadGroup(groupId).catch(() => {
      showToast('그룹을 찾을 수 없어요.');
      navigate('/my', { replace: true });
    });
    loadProjects(groupId);
  }, [groupId, loadGroup, loadProjects, navigate, showToast]);

  if (!currentGroup) return null;

  return (
    <div className="page">
      <div className="group-grid">
        <div>
          <div className="sec-head">
            <h2>{currentGroup.name}</h2>
            <span>프로젝트를 눌러 대시보드로</span>
            <span className="right">
              <button className="btn btn-acc" onClick={() => setCreateProjectOpen(true)}>
                ＋ 새 프로젝트
              </button>
            </span>
          </div>
          <div>
            {projects.map((p) => {
              const done = p.status === 'DONE';
              return (
                <div
                  key={p.id}
                  className="p-card"
                  onClick={() => navigate(`/groups/${groupId}/projects/${p.id}`)}
                >
                  <div style={{ flex: 1 }}>
                    <h3>{p.name}</h3>
                    <div className="meta">
                      {p.startDate} – {p.endDate} · {p.destination} · {p.budgetHeadcount}인
                      {p.transportPref && ` · ${TRANSPORT_LABEL[p.transportPref]}`}
                    </div>
                    <span className={`status ${done ? 'st-done' : 'st-plan'}`}>
                      {done ? '완료 — 편집 가능' : '계획 중'}
                    </span>
                  </div>
                  {/* flat 모델 — 완료 여부와 무관하게 모든 멤버가 삭제할 수 있다 */}
                  <div className="ops">
                    <button
                      className="op"
                      onClick={(e) => {
                        e.stopPropagation();
                        setDeleteProjectTarget(p);
                      }}
                    >
                      🗑
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div>
          <div className="pnl">
            <h3>
              친구{' '}
              <span style={{ fontWeight: 400, color: 'var(--ink2)', fontSize: 11 }}>
                누구나 코드 재발급 가능
              </span>
            </h3>
            <div className="code-chip">
              <span>{currentGroup.inviteCode}</span>
            </div>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <button className="btn btn-gh" style={{ flex: 1 }} onClick={handleCopyCode}>
                복사
              </button>
              <button className="btn btn-gh" style={{ flex: 1 }} onClick={handleReissue}>
                재발급
              </button>
            </div>
            <div>
              {currentGroup.members.map((m) => (
                <div key={m.userId} className="f-row">
                  <span className="mini-av" style={{ background: m.avatarColor }}>
                    {m.nickname[0]}
                  </span>
                  {m.nickname}
                  <span className={m.online ? 'on-dot' : 'off-dot'} />
                </div>
              ))}
            </div>
            <button className="leave-btn" onClick={() => setLeaveOpen(true)}>
              그룹 나가기
            </button>
          </div>
        </div>
      </div>

      <LeaveGroupModal
        open={leaveOpen}
        group={currentGroup}
        onClose={() => setLeaveOpen(false)}
      />
      <CreateProjectModal
        open={createProjectOpen}
        groupId={groupId}
        onClose={() => setCreateProjectOpen(false)}
      />
      <DeleteProjectModal
        open={!!deleteProjectTarget}
        project={deleteProjectTarget}
        onClose={() => setDeleteProjectTarget(null)}
      />
    </div>
  );
}
