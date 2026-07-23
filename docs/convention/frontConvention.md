# 프론트엔드 코딩 컨벤션

> React + TypeScript 기반 프로젝트 공통 규칙


## 1. 디렉터리 구조

**기능(feature) 기준으로 나눈다.** 파일 종류(components/, hooks/, utils/)로 나누면 기능 하나를 고칠 때 폴더 다섯 개를 오간다.

```
src/
├── app/                    # 앱 진입점, 라우터, 전역 프로바이더
│   ├── App.tsx
│   ├── router.tsx
│   └── providers/
├── pages/                  # 라우트 단위 페이지
│   └── ProjectDetail/
│       ├── index.tsx
│       └── components/     # 이 페이지에서만 쓰는 컴포넌트
├── features/               # 도메인 기능 단위
│   └── auth/
│       ├── api/            # 이 기능의 API 호출
│       ├── components/
│       ├── hooks/
│       ├── types.ts
│       └── index.ts        # 외부 공개 항목만 export
├── global/                 # 도메인 무관 공용
│   ├── ui/                 # Button, Modal, Input …
│   ├── hooks/              # useDebounce, useMediaQuery …
│   ├── util/               # 유틸 함수
│   └── api/                # axios 인스턴스, 인터셉터
├── types/                  # 전역 타입
└── constants/
```

### 의존 방향

```
pages → features → global
```

**역방향 import를 금지한다.** `global`이 `features`를 참조하는 순간 공용 모듈이 아니다.

**feature 간 직접 참조도 금지한다.** `features/auth`가 `features/project`의 내부 파일을 가져오면 두 기능이 한 덩어리가 된다. 필요하면 `index.ts`를 통해서만 접근하고, 공유가 잦아지면 `global`로 올린다.

```ts
// ❌ 내부 경로 직접 참조
import { parseToken } from '@/features/auth/util/parseToken';

// ✅ 공개 인터페이스로만
import { parseToken } from '@/features/auth';
```

### 경로 별칭

상대 경로 `../../../`를 금지한다. 같은 폴더 내부(`./`)만 상대 경로를 허용한다.

```ts
// tsconfig.json
{ "compilerOptions": { "paths": { "@/*": ["./src/*"] } } }
```

---

## 2. 네이밍

| 대상 | 규칙 | 예시 |
|---|---|---|
| 컴포넌트 파일·폴더 | PascalCase | `UserProfile.tsx` |
| 그 외 파일 | camelCase | `formatDate.ts` |
| 컴포넌트 | PascalCase | `function UserProfile()` |
| 변수·함수 | camelCase | `const userName`, `formatDate()` |
| 상수 | UPPER_SNAKE_CASE | `const MAX_RETRY = 3` |
| 타입·인터페이스 | PascalCase | `type User`, `interface Props` |
| 커스텀 훅 | `use` + camelCase | `useUserProfile()` |
| 불리언 | `is` / `has` / `can` / `should` | `isLoading`, `hasPermission` |
| 이벤트 핸들러(내부) | `handle` + 동작 | `handleSubmit` |
| 이벤트 핸들러(props) | `on` + 동작 | `onSubmit` |
| 비동기 함수 | 동사로 시작 | `fetchUsers()`, `createOrder()` |

### 이름에 대한 원칙

**축약하지 않는다.** IDE가 자동완성해 준다.

```ts
// ❌
const usr = getUsr();
const btnClk = () => {};

// ✅
const user = getUser();
const handleButtonClick = () => {};
```

**단, 관용어는 예외다.** `id`, `url`, `props`, `ref`, `params`, `i`(짧은 루프 인덱스)는 그대로 쓴다.

**타입 이름에 접두사를 붙이지 않는다.**

```ts
// ❌ IUser, TUser, UserInterface
// ✅ User
```

---

## 3. TypeScript

### `any`를 쓰지 않는다

타입을 모르면 `unknown`을 쓰고 좁혀서 사용한다. `any`는 그 값이 지나가는 모든 코드의 타입 검사를 끈다.

```ts
// ❌
function parse(data: any) {
  return data.items.map((i: any) => i.name);
}

// ✅
function parse(data: unknown): string[] {
  if (!isApiResponse(data)) throw new Error('Invalid response');
  return data.items.map((item) => item.name);
}
```

**불가피하면 `eslint-disable`과 이유를 함께 남긴다.** 조용히 쓰지 않는다.

```ts
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- 외부 SDK가 타입을 제공하지 않음
```

### 타입 단언(`as`)보다 타입 가드

`as`는 "컴파일러야 믿어라"는 선언이고, 틀리면 런타임에서 터진다.

```ts
// ❌
const user = data as User;

// ✅
function isUser(value: unknown): value is User {
  return typeof value === 'object' && value !== null && 'id' in value;
}
```

**예외** — `as const`, 테스트 코드의 목 데이터는 허용한다.

### `interface`와 `type`

**객체 형태는 `interface`, 그 외는 `type`.** 선언 병합이 필요 없으면 어느 쪽이든 동작하므로, 팀 내에서 하나로 통일하는 것이 목적이다.

```ts
interface User {
  id: string;
  name: string;
}

type Status = 'idle' | 'loading' | 'error';
type UserId = User['id'];
```

### 유니온을 활용해 불가능한 상태를 없앤다

```ts
// ❌ isLoading이면서 error도 있는 상태가 타입상 가능하다
interface State {
  isLoading: boolean;
  data?: User;
  error?: Error;
}

// ✅ 세 상태 중 하나만 존재한다
type State =
  | { status: 'loading' }
  | { status: 'success'; data: User }
  | { status: 'error'; error: Error };
```

### enum 대신 유니온 또는 `as const`

TS `enum`은 런타임 코드를 생성하고 트리셰이킹이 안 된다.

```ts
// ❌
enum Role { Admin, Member }

// ✅
const ROLE = { ADMIN: 'ADMIN', MEMBER: 'MEMBER' } as const;
type Role = (typeof ROLE)[keyof typeof ROLE];
```

---

## 4. 컴포넌트

### 기본 형태

```tsx
interface Props {
  userId: string;
  onSelect?: (id: string) => void;
}

export function UserCard({ userId, onSelect }: Props) {
  // 1. 훅
  const { data, isLoading } = useUser(userId);

  // 2. 파생 값
  const displayName = data?.nickname ?? '알 수 없음';

  // 3. 핸들러
  const handleClick = () => onSelect?.(userId);

  // 4. 조기 반환
  if (isLoading) return <Skeleton />;
  if (!data) return null;

  // 5. JSX
  return <button onClick={handleClick}>{displayName}</button>;
}
```

- **함수 선언문 + named export**를 기본으로 한다. `React.FC`는 쓰지 않는다(암묵적 `children`, 제네릭 제약).
- **props는 구조 분해**로 받는다.
- **파일당 하나의 컴포넌트를 export한다.** 그 파일에서만 쓰는 작은 하위 컴포넌트는 같은 파일에 두되, export하지 않는다.
- **default export를 쓰지 않는다.** 이름이 제각각이 되고 자동완성이 약하다. (라우트 lazy 로딩 등 프레임워크가 요구하는 경우만 예외)

### 조건부 렌더링

```tsx
// ❌ items.length가 0이면 화면에 "0"이 출력된다
{items.length && <List items={items} />}

// ✅
{items.length > 0 && <List items={items} />}
```

**삼항 연산자를 중첩하지 않는다.** 두 단계부터는 조기 반환이나 별도 컴포넌트로 분리한다.

### 컴포넌트를 분리하는 기준

줄 수가 아니라 **책임**으로 나눈다. 다만 아래 신호가 보이면 대개 분리 대상이다.

- 200줄을 넘는다
- 훅이 5개 이상이다
- JSX 안에 `map` + 조건부 + 이벤트 핸들러가 3중으로 얽혀 있다
- 이름 붙이기가 어렵다 (= 하는 일이 여러 개다)

**단, 재사용하지 않는데 미리 쪼개지 않는다.** props를 6개 넘겨야 하는 하위 컴포넌트는 대개 분리가 잘못된 것이다.

### 컴포넌트 안에서 컴포넌트를 정의하지 않는다

```tsx
// ❌ 렌더링마다 새 타입이 되어 하위 트리가 통째로 언마운트된다
function Parent() {
  function Child() { return <div />; }
  return <Child />;
}
```

---

## 5. 훅

### 규칙

- **최상위에서만 호출한다.** 조건문·반복문·중첩 함수 안에서 호출하지 않는다.
- **`eslint-plugin-react-hooks`의 경고를 끄지 않는다.** 특히 `exhaustive-deps`. 의존성을 빼고 싶다면 코드 구조가 잘못된 것이다.

### 커스텀 훅으로 분리하는 기준

**같은 로직이 두 번째로 등장할 때** 분리한다. 처음부터 추상화하면 대개 틀린 추상화가 된다.

```ts
export function useDebounce<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return debounced;
}
```

- **훅은 값 또는 객체를 반환한다.** 반환 값이 3개를 넘으면 배열 대신 객체로 반환한다(호출부에서 이름이 뒤바뀌는 사고 방지).
- **훅 안에서 JSX를 반환하지 않는다.** 그건 컴포넌트다.

### `useEffect`를 쓰기 전에 멈춘다

`useEffect`는 **외부 시스템과 동기화할 때만** 쓴다(구독, 타이머, DOM 이벤트, 로깅). 아래는 전부 잘못된 사용이다.

```tsx
// ❌ 파생 상태를 effect로 만든다
const [fullName, setFullName] = useState('');
useEffect(() => setFullName(`${first} ${last}`), [first, last]);

// ✅ 렌더링 중에 계산한다
const fullName = `${first} ${last}`;
```

```tsx
// ❌ 이벤트에 대한 반응을 effect로 처리한다
useEffect(() => {
  if (submitted) sendRequest();
}, [submitted]);

// ✅ 이벤트 핸들러에서 직접 호출한다
const handleSubmit = () => sendRequest();
```

서버 데이터 페칭은 `useEffect` + `useState` 조합 대신 **데이터 페칭 라이브러리**(TanStack Query 등)를 쓴다. 로딩·에러·캐시·중복 요청·경쟁 조건을 직접 구현하면 반드시 빠뜨린다.

---

## 6. 상태 관리

### 상태를 두는 위치

**아래에서 위로 올린다.** 필요해지기 전에 전역으로 올리지 않는다.

```
컴포넌트 지역 상태 (useState)
    ↓ 두 형제가 공유해야 하면
가장 가까운 공통 부모로 리프팅
    ↓ prop drilling이 3단계를 넘으면
Context (자주 안 바뀌는 값: 테마, 로그인 사용자)
    ↓ 전역이면서 자주 바뀌면
전역 상태 라이브러리 (Zustand, Jotai …)
```

### 서버 상태와 클라이언트 상태를 구분한다

가장 흔한 설계 실수는 **서버 데이터를 전역 스토어에 복사해 두는 것**이다. 그 순간 캐시 무효화를 직접 관리해야 한다.

| 종류 | 예시 | 도구 |
|---|---|---|
| **서버 상태** | 사용자 목록, 게시글 | TanStack Query 등 |
| **클라이언트 상태** | 모달 열림, 폼 입력, 필터 | useState / 전역 스토어 |

### 파생 상태를 저장하지 않는다

```tsx
// ❌ 두 상태가 어긋날 수 있다
const [items, setItems] = useState<Item[]>([]);
const [count, setCount] = useState(0);

// ✅ 계산한다
const count = items.length;
```

계산 비용이 실제로 큰 경우에만 `useMemo`를 붙인다.

### 상태를 갱신할 때 불변성을 지킨다

```ts
// ❌
items.push(newItem);
setItems(items);

// ✅
setItems((prev) => [...prev, newItem]);
```

**이전 상태에 의존하면 반드시 함수형 업데이트를 쓴다.**

---

## 7. 스타일

**하나의 방식만 쓴다.** CSS Modules / Tailwind / CSS-in-JS 중 프로젝트 시작 시 정하고, 섞지 않는다.

### 공통 규칙

- **매직 넘버를 쓰지 않는다.** 색상·간격·폰트는 토큰(CSS 변수 또는 테마 객체)으로 정의하고 그것만 참조한다.
- **인라인 스타일은 동적 값에만** 쓴다(계산된 위치·크기 등).
- **`!important`를 쓰지 않는다.** 필요하다면 선택자 구조가 잘못됐다.
- **`z-index`는 상수로 관리한다.** `9999` 같은 값이 코드에 흩어지면 순서를 아무도 모른다.

```ts
export const Z_INDEX = { dropdown: 10, modal: 100, toast: 1000 } as const;
```

---

## 8. API 통신

### 계층을 분리한다

**컴포넌트에서 `fetch`나 `axios`를 직접 호출하지 않는다.**

```
컴포넌트 → 훅 → api 함수 → HTTP 클라이언트
```

```ts
// global/api/client.ts — 인스턴스와 인터셉터
export const client = axios.create({ baseURL: import.meta.env.VITE_API_URL });

// features/user/api/getUser.ts — 엔드포인트 하나당 함수 하나
export async function getUser(id: string): Promise<User> {
  const { data } = await client.get<User>(`/users/${id}`);
  return data;
}

// features/user/hooks/useUser.ts — 컴포넌트가 쓰는 인터페이스
export function useUser(id: string) {
  return useQuery({ queryKey: ['user', id], queryFn: () => getUser(id) });
}
```

### 규칙

- **API 응답 타입을 명시한다.** 서버 스키마가 있으면 코드 생성(OpenAPI, GraphQL Codegen)을 우선한다.
- **에러 처리는 인터셉터에서 공통 처리하고**, 화면별 분기가 필요한 것만 개별 처리한다.
- **URL 문자열을 컴포넌트에 두지 않는다.**
- **환경 변수로 분리한다.** 코드에 도메인을 하드코딩하지 않는다.

---

## 9. 주석

**"무엇"이 아니라 "왜"를 쓴다.** 무엇을 하는지는 코드가 이미 말하고 있다.

```ts
// ❌ 사용자 목록을 필터링한다
const activeUsers = users.filter((u) => u.isActive);

// ✅ 탈퇴 회원은 통계에서 제외해야 한다 (기획 확정 2026-07-10)
const activeUsers = users.filter((u) => u.isActive);
```

- **주석이 필요한 코드는 대개 이름이 나쁜 코드다.** 주석을 달기 전에 함수나 변수로 추출해 이름을 붙일 수 있는지 본다.
- **`TODO`에는 담당자와 이유를 남긴다.** `// TODO(광민): API 확정 후 교체 — #123`
- **주석 처리된 코드를 커밋하지 않는다.** Git이 기억한다.

---





