## **코드 네이밍 컨벤션**

### **패키지 및 클래스**

- **패키지 이름**: `camelCase` 사용. (ex: `domain.user`, `global.apiPayload`).
- **클래스 이름**: `PascalCase` 사용.
    - 컨트롤러: `UserController`, `ScenarioController`
    - 서비스: `UserService`, `ScenarioService`
    - 엔티티: `User`, `Scenario`
    - DTO: `UserRequest`, `UserResponse`

### **메서드 및 변수**

- 메서드: `camelCase` 사용.
    - ex: `getUserById()`, `updateScenario()`
- 변수: `camelCase` 사용.
    - ex: `userName`, `scenarioId`

---

## **코딩 스타일**

### **1) 클래스 구조**

1. `@Annotation` → 클래스 선언 순서
2. 상수 필드 → 멤버 변수
3. 생성자 → 메서드
4. 내부 클래스(필요 시)

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }
}
```

---

### **2) 메서드 순서**

1. **Public → Protected → Private** 순서로 배치.
2. CRUD 메서드 순서:
    - Create → Read → Update → Delete

---

## **JPA 관련 규칙([양방향])**

### **1) 엔티티**

- 클래스 이름은 단수형으로 작성 (`User`, `Scenario`).
- 변수명은 camelCase로 작성.
- 필드 순서:
    1. 식별자 (ID)
    2. 필드
    3. 연관 관계 (OneToOne, OneToMany 등)

```java
@Entity
@Table(name = "user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;
}
```

### **2) Repository**

- 이름은 `{Entity}Repository`로 작성.
- Spring Data JPA 메서드 이름 규칙을 따름.
    - `findBy`, `existsBy`, `deleteBy`.

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findById(Long id);
}
```

---

## 6. **DTO 규칙(record)**

- DTO 클래스는 `Request`, `Response`로 구분.
- 필드는 엔티티와 1:1 매핑하지 말고 필요한 데이터만 포함.
- class를 사용할 것
    - 바꾸는 부분은 무조건 converter를 사용할 것!

```java
public class UserReqDTO {
		
		@Getter
		public record SignUp(
			String name,
			String email
		) {
		}
}
```

```java
public class UserResDTO {

		@Builder
		public record SignUp(
				 String name,
			   String email
		) {
		}
}
```

---

## 7. **예외 처리**

- 글로벌 예외 처리를 위한 `@ControllerAdvice` 클래스 작성.
- 형식은 `{Entity}Exception` 으로 할 것

```java
public class UserException extends GeneralException {
    public UserException(ErrorStatus errorStatus) {super(errorStatus);}
}
```

---

## 8. Converter

- converter의 메소드 이름은 to+만들고자하는 오브젝트 이름으로 할 것
    - ex) toChat(), toUser() 등

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE) 
public static Object toObject() {
    ...
}
```

---

## 9. Service

- interface와 구현 클래스를 나누고 구현 클래스의 이름은 interface뒤에 impl을 붙일 것
- CQRS 패턴을 사용할 것
- Service의 메소드 이름은 camelCase를 사용할 것

```java
public interface UserQueryService {
    User findUserById(Long id);
}
```

```java
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {

    private final UserRepository userRepository;

    @Override
    public User findUserById(Long id) {
	       User user = userRepository.orElseThrow(() -> new UserException(ErrorStatus.USER_NOT_FOUND));
    }
}
```

---

## 10. Controller

- 메소드는 camelCase를 사용할 것
- RequestMapping에 버전 prefix를 붙이지 않는다 (`/api/...`로 시작)
- RestfulAPI 원칙을 최대한 지킬 것

```java
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/comments")
@Tag(name = "댓글")
public class CommentController {

    private final CommentCommandService commentCommandService;

    @PostMapping("")
    @Operation(summary = "댓글 작성 API", description = "새로운 댓글을 작성하는 API입니다.")
    public ApiResponse<CreateCommentResponse> createComment(
            @Parameter(hidden = true) @LoginUser User user,
            @RequestBody @Valid CreateCommentRequest createCommentRequest
    ) {
        CreateCommentResponse response = commentCommandService.createComment(user, createCommentRequest);
        return ApiResponse.onSuccess(response);
    }
}
```

---

## 11. 응답 통일

```java
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"isSuccess", "status", "code", "message", "result"})
public class CustomResponse<T> {

    @JsonProperty("isSuccess") // isSuccess라는 변수라는 것을 명시하는 Annotation
    private boolean isSuccess;

    @JsonProperty("code")
    private String code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("result")
    private final T result;

    //기본적으로 200 OK를 사용하는 성공 응답 생성 메서드
    public static <T> CustomResponse<T> onSuccess(T result) {
        return new CustomResponse<>(true, String.valueOf(HttpStatus.OK.value()), HttpStatus.OK.getReasonPhrase(), result);
    }

    //상태 코드를 받아서 사용하는 성공 응답 생성 메서드
    public static <T> CustomResponse<T> onSuccess(HttpStatus status, T result) {
        return new CustomResponse<>(true, String.valueOf(status.value()), status.getReasonPhrase(), result);
    }

    //실패 응답 생성 메서드 (데이터 포함)
    public static <T> CustomResponse<T> onFailure(String code, String message, T result) {
        return new CustomResponse<>(false, code, message, result);
    }

    //실패 응답 생성 메서드 (데이터 없음)
    public static <T> CustomResponse<T> onFailure(String code, String message) {
        return new CustomResponse<>(false, code, message, null);
    }
}
```