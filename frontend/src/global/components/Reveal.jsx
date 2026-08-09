import { useScrollReveal } from '../hooks/useScrollReveal';

export function Reveal({
  children,
  as: Tag = 'div',
  delay = 0,
  className = '',
  revealOptions,
  ...rest
}) {
  const [ref, isVisible] = useScrollReveal(revealOptions);

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