import emptyImg from "../../assets/img/empty.png";
import "./spinner.css";

// 빈 상태 안내 — 이음이 캐릭터와 함께 "왜 비었는지·다음에 뭘 하면 되는지"를
// 보여준다. 로딩과 달리 대기 중이 아니므로 캐릭터는 튀지 않고 가만히 서 있다.
// action 은 선택적 버튼 등(없으면 문구만). img 로 상태별 캐릭터를 갈아끼운다
// (404·에러 등은 각자 다른 포즈를 넘긴다. 기본은 빈 목록용 empty).
export function EmptyState({ title, desc, action, img = emptyImg }) {
  return (
    <div className="estate">
      <img className="estate__img" src={img} alt="" aria-hidden="true" />
      {title && <b className="estate__title">{title}</b>}
      {desc && <span className="estate__desc">{desc}</span>}
      {action && <div className="estate__action">{action}</div>}
    </div>
  );
}
