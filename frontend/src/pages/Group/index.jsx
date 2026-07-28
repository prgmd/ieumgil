import '../My/shared/styles/index.css';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../My/shared/stores/authStore';
import { useGroupStore, selectIsAdmin } from '../My/shared/stores/groupStore';
import { useToastStore } from '../My/shared/stores/toastStore';
import KickMemberModal from './components/KickMemberModal';
import LeaveGroupModal from './components/LeaveGroupModal';
import CreateProjectModal from './components/CreateProjectModal';
import DeleteProjectModal from './components/DeleteProjectModal';

export function GroupPage() {
  const { groupId } = useParams();
  const currentUser = useAuthStore((s) => s.currentUser);
  const { currentGroup, projects, loadProjects, reissueInviteCode } = useGroupStore();
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();

  const [kickTarget, setKickTarget] = useState(null); // 방출 모달에 넘길 멤버
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

  // RequireMembership이 이미 currentGroup을 채워둔 상태로 이 컴포넌트에 들어온다.
  const isAdmin = selectIsAdmin(currentGroup, currentUser.id);

  useEffect(() => {
    loadProjects(groupId);
  }, [groupId, loadProjects]);

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
              const canDelete = !done || isAdmin; // GRP-04: 완료 프로젝트는 관리자만
              return (
                <div
                  key={p.id}
                  className="p-card"
                  onClick={() => navigate(`/groups/${groupId}/projects/${p.id}`)}
                >
                  <div style={{ flex: 1 }}>
                    <h3>{p.name}</h3>
                    <div className="meta">
                      {p.startDate} – {p.endDate} · {p.destination} · {p.headcount}인
                    </div>
                    <span className={`status ${done ? 'st-done' : 'st-plan'}`}>
                      {done ? '완료 — 편집 가능' : '계획 중'}
                    </span>
                  </div>
                  {canDelete && (
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
                  )}
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
                방장만 방출/재발급
              </span>
            </h3>
            <div className="code-chip">
              <span>{currentGroup.inviteCode}</span>
            </div>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <button className="btn btn-gh" style={{ flex: 1 }} onClick={handleCopyCode}>
                복사
              </button>
              {isAdmin && (
                <button className="btn btn-gh" style={{ flex: 1 }} onClick={handleReissue}>
                  재발급
                </button>
              )}
            </div>
            <div>
              {currentGroup.members.map((m) => (
                <div key={m.userId} className="f-row">
                  <span className="mini-av" style={{ background: m.avatarColor }}>
                    {m.nickname[0]}
                  </span>
                  {m.nickname}
                  {m.role === 'ADMIN' && <span className="admin-badge">방장</span>}
                  <span className={m.online ? 'on-dot' : 'off-dot'} />
                  {isAdmin && m.userId !== currentUser.id && (
                    <button className="kick" onClick={() => setKickTarget(m)}>
                      방출
                    </button>
                  )}
                </div>
              ))}
            </div>
            <button className="leave-btn" onClick={() => setLeaveOpen(true)}>
              그룹 나가기
            </button>
          </div>
        </div>
      </div>

      <KickMemberModal
        open={!!kickTarget}
        groupId={groupId}
        member={kickTarget}
        onClose={() => setKickTarget(null)}
      />
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
        isAdmin={isAdmin}
        onClose={() => setDeleteProjectTarget(null)}
      />
    </div>
  );
}
