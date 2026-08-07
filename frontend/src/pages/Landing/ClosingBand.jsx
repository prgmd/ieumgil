import { Reveal } from '../../global/components/Reveal';

export function ClosingBand() {
  return (
    <Reveal
      as="section"
      className="closing-band"
      revealOptions={{ threshold: 0, rootMargin: '0px' }}
    >
      <p>동선 계산부터 예산 관리까지, 당신의 여행 조각들을 완벽하게 이어드립니다.</p>
    </Reveal>
  );
}