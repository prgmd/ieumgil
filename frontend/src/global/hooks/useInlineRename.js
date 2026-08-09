import { useState } from 'react';

/**
 * 인라인 이름 수정 공통 로직 — ✎ 로 열어 input 에서 고치고 blur/Enter 로 저장,
 * 빈 값이거나 원래와 같으면 조용히 취소한다. 어떤 항목을 편집 중인지 key 로 구분해
 * 단일(그룹명)과 목록(그룹 카드) 양쪽에서 함께 쓴다.
 *
 * @param {(key, value) => Promise} rename 실제 저장. key 는 start 에 넘긴 값.
 * @param {() => void} [onSuccess] 저장 성공 후(보통 토스트)
 * @param {() => void} [onError]   저장 실패 시(보통 토스트)
 */
export function useInlineRename({ rename, onSuccess, onError }) {
  const [editingKey, setEditingKey] = useState(null);
  const [value, setValue] = useState('');

  const isEditing = (key) => editingKey === key;

  const start = (key, current) => {
    setEditingKey(key);
    setValue(current ?? '');
  };

  const cancel = () => setEditingKey(null);

  // current = 편집을 연 시점의 원래 이름(무변경 판정용)
  async function commit(current) {
    const key = editingKey;
    const trimmed = value.trim();
    setEditingKey(null);
    if (!trimmed || trimmed === current) return; // 빈 값·무변경이면 조용히 취소
    try {
      await rename(key, trimmed);
      onSuccess?.();
    } catch {
      onError?.();
    }
  }

  return { value, setValue, isEditing, start, cancel, commit };
}
