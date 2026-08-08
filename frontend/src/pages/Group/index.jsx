import '../My/shared/styles/index.css';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useGroupDetail } from '../../features/group/hooks/useGroupDetail';
import { useProjects } from '../../features/group/hooks/useProjects';
import { isTripFinished } from '../../features/group/util/tripStatus';
import { useToastStore } from '../../global/stores/toastStore';
import { ROUTES } from '../../global/constants/routes';
import { onEnter } from '../../global/util/onEnter';
import LeaveGroupModal from './components/LeaveGroupModal';
import CreateProjectModal from './components/CreateProjectModal';
import EditProjectModal from './components/EditProjectModal';
import DeleteProjectModal from './components/DeleteProjectModal';
import { AppBar } from '../My/shared/ui/AppBar';
import { Avatar } from '../My/shared/ui/Avatar';
import { TRANSPORT_LABEL } from '../My/shared/ui/transportLabels';

export function GroupPage() {
  // 라우트 파라미터는 문자열 — 서버의 숫자 ID와 맞추려면 변환이 필요하다.
  const groupId = Number(useParams().groupId);
  
  // groupId만 들고 들어오므로 그룹·프로젝트를 URL 파라미터로 직접 조회한다.
  const { group, status, reissueInviteCode, renameGroup, leaveGroup } =
    useGroupDetail(groupId);
  const {
    projects,
    status: projectsStatus,
    createProject,
    updateProject,
    deleteProject,
  } = useProjects(groupId);
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();

  const [leaveOpen, setLeaveOpen] = useState(false);
  const [renaming, setRenaming] = useState(false); // 그룹명 인라인 수정 중인지
  const [nameDraft, setNameDraft] = useState('');
  const [reissuing, setReissuing] = useState(false); // 초대코드 재발급 요청 중인지
  const [createProjectOpen, setCreateProjectOpen] = useState(false);
  const [editProjectTarget, setEditProjectTarget] = useState(null);
  const [deleteProjectTarget, setDeleteProjectTarget] = useState(null);

  async function handleCopyCode() {
    try {
      await navigator.clipboard.writeText(group.inviteCode);
      showToast('초대 코드가 복사됐어요 🔗');
    } catch {
      showToast('복사에 실패했어요 — 코드를 직접 선택해 복사해주세요.');
    }
  }

  // 개인 페이지의 그룹 카드와 같은 인라인 수정 규칙 — 빈 값이면 조용히 취소한다.
  function startRename() {
    setNameDraft(group.name);
    setRenaming(true);
  }

  async function commitRename() {
    const trimmed = nameDraft.trim();
    setRenaming(false);
    if (!trimmed || trimmed === group.name) return;
    try {
      await renameGroup(trimmed);
      showToast('그룹명이 수정됐어요 ✓');
    } catch {
      showToast('그룹명은 2~20자로 입력해주세요.');
    }
  }

  async function handleReissue() {
    if (reissuing) return; // 연타 시 중복 재발급 방지
    setReissuing(true);
    try {
      await reissueInviteCode();
      showToast('코드 재발급 — 기존 코드는 즉시 무효화됐어요');
    } catch {
      showToast('코드 재발급에 실패했어요. 잠시 후 다시 시도해주세요.');
    } finally {
      setReissuing(false);
    }
  }

  // 초대코드 남은 유효기간 — 로컬 날짜(자정 기준)로 계산한다.
  function inviteDaysLeft(iso) {
    if (!iso) return null;
    const now = new Date();
    const exp = new Date(iso);
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const startOfExp = new Date(exp.getFullYear(), exp.getMonth(), exp.getDate());
    return Math.round((startOfExp - startOfToday) / 86400000);
  }

  // 없는 그룹·권한 없는 그룹·잘못된 URL(/groups/abc)이면 개인 페이지로 되돌린다.
  useEffect(() => {
    if (status !== 'error') return;
    showToast('그룹을 찾을 수 없어요.');
    navigate(ROUTES.my, { replace: true });
  }, [status, navigate, showToast]);

  // 그룹 조회가 끝나기 전에는 완전 백지 대신 상단바와 안내를 보여준다.
  // (status === 'error' 는 위 effect 가 개인 페이지로 돌려보낸다.)
  if (!group) {
    return (
      <>
        <AppBar crumbs={[{ label: '개인 페이지', to: ROUTES.my }]} />
        <div className="page">
          <p className="nodata">불러오는 중…</p>
        </div>
      </>
    );
  }

  return (
    <>
      <AppBar crumbs={[{ label: '개인 페이지', to: ROUTES.my }, { label: group.name }]} />
      <div className="page">
      {/* 머리글은 grid 밖으로 — 그래야 좌측 프로젝트 리스트와 우측 친구 패널이
          같은 라인에서 시작한다(My 페이지와 동일 구조) */}
      <div className="sec-head">
        {/* 그룹명도 이 화면에서 바로 수정한다 — 상단바·대시보드 경로에 함께 반영된다 */}
        {renaming ? (
          <input
            className="rename-input sec-rename"
            value={nameDraft}
            autoFocus
            maxLength={20}
            onChange={(e) => setNameDraft(e.target.value)}
            onBlur={commitRename}
            onKeyDown={onEnter((e) => e.currentTarget.blur())}
          />
        ) : (
          <h2 className="sec-title">
            {group.name}
            <button
              type="button"
              className="sec-op"
              title="그룹명 수정"
              onClick={startRename}
            >
              ✎
            </button>
          </h2>
        )}
        <span>프로젝트를 눌러 대시보드로</span>
        <span className="right">
          <button className="btn btn-acc" onClick={() => setCreateProjectOpen(true)}>
            ＋ 새 프로젝트
          </button>
        </span>
      </div>
      <div className="group-grid">
        <div>
          <div>
            {/* 노데이터 (QA 배치2) — 로딩 중 깜빡임·조회 실패의 빈 그룹 위장을 막기 위해
                status 로 판정한다. 진짜 비었을 때(loaded)만 노데이터, error 면 실패 문구. */}
            {projectsStatus === 'loaded' && projects.length === 0 && (
              <p className="nodata">
                아직 프로젝트가 없어요 — 위 <b>＋ 새 프로젝트</b>로 첫 여행
                계획을 시작해보세요.
              </p>
            )}
            {projectsStatus === 'error' && (
              <p className="nodata">
                프로젝트 목록을 불러오지 못했어요. 잠시 후 다시 시도해주세요.
              </p>
            )}
            {projects.map((p) => {
              // 서버 status 는 아직 전부 PLANNING 이라 종료일로 판정한다
              const done = isTripFinished(p);
              return (
                <div
                  key={p.projectId}
                  className="p-card"
                  onClick={() => navigate(ROUTES.project(groupId, p.projectId))}
                >
                  <div style={{ flex: 1 }}>
                    <h3>{p.name}</h3>
                    <div className="meta">
                      {p.startDate} – {p.endDate} · {p.destination} · {p.budgetHeadcount}인
                      {p.transportPrefs?.length > 0 &&
                        ` · ${p.transportPrefs.map((v) => TRANSPORT_LABEL[v]).join(', ')}`}
                    </div>
                    <span className={`status ${done ? 'st-done' : 'st-plan'}`}>
                      {done ? '여행 완료' : '계획 중'}
                    </span>
                  </div>
                  {/* flat 모델 — 완료 여부와 무관하게 모든 멤버가 수정·삭제할 수 있다.
                      아이콘·hover 노출 규칙은 개인 페이지의 그룹 카드(.g-card .ops)와 같다. */}
                  <div className="ops">
                    <button
                      className="op"
                      title="프로젝트 수정"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditProjectTarget(p);
                      }}
                    >
                      ✎
                    </button>
                    <button
                      className="op"
                      title="프로젝트 삭제"
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
              <span>{group.inviteCode}</span>
            </div>
            {(() => {
              const d = inviteDaysLeft(group.inviteExpiresAt);
              if (d == null) return null;
              const label =
                d > 0 ? `${d}일 후 만료` : d === 0 ? '오늘 만료' : '만료됨';
              return (
                <p style={{ fontSize: 11, color: 'var(--ink2)', margin: '0 0 8px' }}>
                  {label}
                </p>
              );
            })()}
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <button className="btn btn-gh" style={{ flex: 1 }} onClick={handleCopyCode}>
                복사
              </button>
              <button
                className="btn btn-gh"
                style={{ flex: 1 }}
                onClick={handleReissue}
                disabled={reissuing}
              >
                재발급
              </button>
            </div>
            <div>
              {group.members.map((m) => (
                <div key={m.memberId} className="f-row">
                  <Avatar memberId={m.memberId} nickname={m.nickname} profileImg={m.profileImg} />
                  {m.nickname}
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
        group={group}
        onLeave={leaveGroup}
        onClose={() => setLeaveOpen(false)}
      />
      <CreateProjectModal
        open={createProjectOpen}
        onCreate={createProject}
        onClose={() => setCreateProjectOpen(false)}
        defaultHeadcount={group?.members?.length || 1}
      />
      {/* 열려 있을 때만 마운트한다 — 다른 프로젝트의 ✎ 를 누르면 폼이 그 프로젝트
          값으로 새로 잡히도록(key) 하기 위함 */}
      {editProjectTarget && (
        <EditProjectModal
          key={editProjectTarget.projectId}
          open
          project={editProjectTarget}
          onUpdate={updateProject}
          onClose={() => setEditProjectTarget(null)}
        />
      )}
      <DeleteProjectModal
        open={!!deleteProjectTarget}
        project={deleteProjectTarget}
        onDelete={deleteProject}
        onClose={() => setDeleteProjectTarget(null)}
      />
      </div>
    </>
  );
}
