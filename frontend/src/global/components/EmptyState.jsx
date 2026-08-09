import loadingImg from "../../assets/img/loading.png";
import "./spinner.css";

// 빈 상태 안내 — 이음이 캐릭터와 함께 "왜 비었는지·다음에 뭘 하면 되는지"를
// 보여준다. 로딩과 달리 대기 중이 아니므로 캐릭터는 튀지 않고 가만히 서 있다.
// action 은 선택적 버튼 등(없으면 문구만).
export function EmptyState({ title, desc, action }) {
  return (
    <div className="estate">
      <img className="estate__img" src={loadingImg} alt="" aria-hidden="true" />
      {title && <b className="estate__title">{title}</b>}
      {desc && <span className="estate__desc">{desc}</span>}
      {action && <div className="estate__action">{action}</div>}
    </div>
  );
}
