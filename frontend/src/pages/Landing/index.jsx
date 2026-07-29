import { LandingNav } from './LandingNav';
import { HeroSection } from './HeroSection';
import { ShowcaseSection } from './ShowcaseSection';
import { ClosingBand } from './ClosingBand';

import './tokens.css';
import './base.css';
import './landing.css';

export function LandingPage() {
  return (
    <div className="landing">
      <LandingNav />
      <main>
        <HeroSection />
        <ShowcaseSection />
        <ClosingBand />
      </main>
    </div>
  );
}
