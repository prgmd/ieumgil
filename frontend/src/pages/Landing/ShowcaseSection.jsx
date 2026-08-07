import { Reveal } from '../../global/components/Reveal';

export function ShowcaseSection() {
  return (
    <section className="showcase">
      <Reveal as="div" className="showcase__intro">
        <h2>모든 여행 계획을 위한 단 하나의 서비스</h2>
        <p>
          복잡한 여행 준비는 이제 그만. 이음길에서 실시간으로 소통하며 완벽한 계획을 세워보세요.
          <br />
          동선 계산부터 예산 관리까지, 당신의 여행 조각들을 완벽하게 이어드립니다.
        </p>
      </Reveal>
    </section>
  );
}
