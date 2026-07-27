import { useScrollReveal } from '../hooks/useScrollReveal';

/**
 * Wraps children in a div that fades/slides into place the first time it
 * enters the viewport. `as` lets callers render a semantic element
 * (section, li, ...) instead of a plain div.
 */
export function Reveal({ children, as: Tag = 'div', delay = 0, className = '', ...rest }) {
  const [ref, isVisible] = useScrollReveal();

  return (
    <Tag
      ref={ref}
      className={`reveal ${isVisible ? 'is-visible' : ''} ${className}`.trim()}
      style={{ transitionDelay: isVisible ? `${delay}ms` : '0ms' }}
      {...rest}
    >
      {children}
    </Tag>
  );
}
