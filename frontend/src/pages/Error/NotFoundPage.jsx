import { Link } from "react-router-dom";
import { EmptyState } from "../../global/components/EmptyState";
import { ROUTES } from "../../global/constants/routes";
import notFoundImg from "../../assets/img/notfound.png";
import "./error.css";

// 존재하지 않는 경로 — 조용한 리다이렉트 대신 이음이가 안내하고 홈 길을 준다.
export function NotFoundPage() {
  return (
    <div className="epage">
      <EmptyState
        img={notFoundImg}
        title="없는 페이지예요"
        desc="주소가 바뀌었거나 사라진 페이지일 수 있어요. 처음 화면에서 다시 시작해보세요."
        action={
          <Link className="btn btn-acc" to={ROUTES.landing}>
            홈으로 가기
          </Link>
        }
      />
    </div>
  );
}
