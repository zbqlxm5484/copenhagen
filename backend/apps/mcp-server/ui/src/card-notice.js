export function cardNotice({ pending, stale }) {
  if (stale) return { title: '이전 장바구니예요', text: '다른 요청으로 장바구니가 변경됐어요. 최신 장바구니를 확인한 뒤 다시 선택해주세요.' };
  if (pending) return { title: '이 카드에서 변경을 요청했어요', text: '중복 요청을 막기 위해 이 카드의 변경 버튼을 잠갔어요. 최신 장바구니에서 처리 결과를 확인해주세요.' };
  return null;
}
export function sameCartVersion(expected, actual) {
  return Number.isSafeInteger(expected) && Number.isSafeInteger(actual) && expected === actual;
}
// Leave a small rounding allowance; never measure against viewport height (resize feedback).
export function cardContentHeight(height) { return Math.ceil(height) + 4; }
