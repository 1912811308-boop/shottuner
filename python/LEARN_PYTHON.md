# Python 上手笔记（写给有 Kotlin / Java 底子的人）

这份笔记不是语法手册，是我把本项目的 Kotlin 算法翻译成 Python 时踩到的具体坑，
每一条都对应仓库里能跑起来的代码。看完能直接读写同量级的工程代码。

---

## 1. 环境：先解决"装不上"

三件事按顺序做，缺一个后面都会卡：

```bash
python -m pip install --upgrade pip          # 一定要用 python -m pip，而不是裸 pip
python -m pip install numpy pillow pytest
python -c "import numpy; print(numpy.__version__)"
```

`python -m pip` 和 `pip` 的区别在于前者绑定到当前解释器。机器上装了多个 Python 时，
裸 `pip` 很可能把包装到另一个版本里，然后你 `import numpy` 依旧报 ModuleNotFoundError。

虚拟环境（等价于 Gradle 的 per-project 依赖隔离）：

```bash
python -m venv .venv
source .venv/Scripts/activate    # Windows Git Bash
# .venv\Scripts\activate.bat     # cmd
```

---

## 2. 语法对照：把 Kotlin 直觉换掉

| Kotlin | Python | 要注意的 |
|---|---|---|
| `val x: Int = 1` | `x: int = 1` | 类型注解只是提示，运行时不校验 |
| `data class Foo(val a: Int)` | `@dataclass(frozen=True) class Foo: a: int` | 要 `from dataclasses import dataclass` |
| `listOf(1,2)` / `mutableListOf` | `[1, 2]` | 只有一种 list，可变 |
| `mapOf` | `{}` | |
| `for (i in 0 until n)` | `for i in range(n)` | `range` 不含右端点，和 `until` 一致 |
| `when (x) { ... }` | `if/elif` 或 `match`（3.10+） | 枚举分支用 dict 分发更常见 |
| `?:` | `or` / `if x is not None` | `or` 会把 0、"" 当真值假，别乱用 |
| `!!` | 无 | 直接抛 AttributeError，没法提前断言 |
| `object Foo { fun bar() }` | 模块级函数 | Python 用模块当命名空间，不需要单例对象 |
| `private` | `_prefix` | 约定，不是强制 |

---

## 3. 类型注解：现代 Python 的写法

项目里每个文件头部都有这一行：

```python
from __future__ import annotations
```

作用是让注解延迟求值。没有它，`def f(x: MyClass | None)` 在 3.9 上会直接 SyntaxError，
而且循环引用会炸。加上之后注解变成字符串，问题全消失。

联合类型用 `X | None` 而不是 `Optional[X]`，这是 3.10+ 的写法，配合上面那行在旧版本也能用。

关键在于：**注解不参与运行**。`def recommend(iso: int)` 传个字符串进去照样跑，
直到某个地方 `"abc" * 2` 出问题。所以边界处要有运行时校验，见 `stats_from_rgb` 里的 `raise ValueError`。

---

## 4. dataclass：和 Kotlin data class 的差别

```python
@dataclass(frozen=True)
class ManualParams:
    iso: int
    shutter_ns: int

    @property
    def exposure(self) -> float:
        return self.iso * self.shutter_ns / 1e9
```

三个差别值得记：

1. **没有主构造函数概念**。字段声明 + 默认值就是构造参数，顺序敏感。
2. **`frozen=True` 才等价于 val**，否则字段可改，且不生成 `__hash__`。
3. **`eq` 自动生成，但含 ndarray 字段时会出错**。所以 `FrameStats` 里我放了 numpy 数组，
   如果它是 dataclass，`==` 会返回数组而不是布尔值，`assert a == b` 直接报
   "truth value of an array is ambiguous"。这也是为什么 Kotlin 侧 `FrameStats` 我故意没用 `data class`。

---

## 5. numpy：把循环换成掩膜

Kotlin 里我是这么算肤色比例的（手写循环 + 计数器）：

```kotlin
if (u in -27..7 && v in 5..45 && yv > 40f) skin++
```

Python 里如果照抄循环，一张 4000×3000 的图要跑几秒。numpy 的写法是**整张图一起算**：

```python
cb = -0.168736 * r - 0.331264 * g + 0.5 * b
cr = 0.5 * r - 0.418688 * g - 0.081312 * b
skin_mask = (cb > -27) & (cb < 7) & (cr > 5) & (cr < 45) & (luma > 40)
skin_ratio = float(np.mean(skin_mask))
```

几个必须记住的点：

- **`&` 不是 `and`**。`and` 对数组会抛 ValueError，必须用位运算符，且每个条件要加括号
  （`&` 优先级高于比较运算符，`a > 1 & b < 2` 会被解析成 `a > (1 & b) < 2`）。
- **dtype 决定溢出行为**。`np.uint8` 的 `200 + 100` 等于 44。所以我在 `stats_from_rgb` 第一行就
  `.astype(np.float32)`，后面所有运算都在浮点上做。
- **避免全分辨率运算**。`step = max(1, min(h, w) // 240)` 这行把 4000×3000 降到约 240×320，
  统计量几乎不变，速度快 200 倍以上。
- **`np.percentile` 一次算多个分位**比调三次快：`np.percentile(luma, [5, 50, 95])`。

---

## 6. 模块与包：`__init__.py` 是干什么的

项目结构：

```
python/
├── shottuner/
│   ├── __init__.py
│   ├── stats.py
│   ├── scene.py
│   └── recommend.py
├── tests/
└── conftest.py
```

- `__init__.py` 让目录成为包，同时**充当对外 API 的白名单**。我在里面只 re-export 了
  公共接口，内部模块名改变不会破坏调用方。
- `python -m shottuner.cli photo.jpg` 里的 `-m` 表示"以模块方式运行"，会先把当前目录加入
  `sys.path`。直接 `python shottuner/cli.py` 会因为相对导入 `from .stats import ...` 而崩掉。
- 测试目录下的 `conftest.py` 是 pytest 的自动加载入口，我用它把 `python/` 加进 `sys.path`，
  这样不安装包也能 import。

---

## 7. pytest：比 JUnit 少写 80% 样板

```python
@pytest.mark.parametrize("scene,gain", [
    (SceneType.INDOOR, 0.18),
    (SceneType.NIGHT, 0.02),
    (SceneType.LANDSCAPE, 1.0),
])
def test_closed_loop_converges(scene, gain):
    recs, lumas = run_closed_loop(scene, gain, frames=16)
    assert all(abs(v - recs[-1].target_luma) <= 0.08 for v in lumas[-4:])
```

要点：

- **不需要类**。函数名 `test_` 开头就会被收集。
- **参数化是原生的**，不用写循环。
- **浮点比较用 `pytest.approx`**：`assert x == pytest.approx(0.0, abs=1e-6)`。
  直接 `==` 在浮点上迟早翻车。
- **断言失败会打印局部变量**，比堆栈有用得多。

运行：`python -m pytest tests -q`，`-q` 是安静模式，`-x` 是首个失败即停。

---

## 8. 我踩过的四个坑

**坑一：可变默认值**

```python
def f(parts=[]):     # 错：所有调用共享同一个 list
    parts.append(1)
```

正确写法是 `def f(parts: list[str] | None = None): parts = parts or []`。
本项目 `_build_detail` 里用的是函数内 `parts: list[str] = []`，每次调用新建，没问题。

**坑二：整数除法**

`4 / 2` 是 `2.0` 不是 `2`。写 `shutter_ns / 1e9` 得到浮点秒，再 `round()` 回整数。
Kotlin 里 `/` 对 Int 是整除，迁移代码时这是最高频的错。

**坑三：浮点累积误差**

闭环仿真跑 16 帧，每帧乘 `2**ev`。如果不做 `round()` 就写回相机参数，
ISO 会变成 `1600.0000000001` 这种值。所以 `final_iso = int(round(iso))` 是必需的，
不只是为了显示好看。

**坑四：中文路径**

Windows 下 `open("D:/AI学习/xx.txt")` 本身没问题，但老版本工具链（尤其 Java / Gradle）
在非 ASCII 路径上会莫名失败。所以本项目把 Android 工具链装在 `.toolchain/` 子目录里，
路径里仍含中文但不影响 Gradle（Gradle 8 + JDK 17 已能处理）。

---

## 9. 什么时候别用 numpy

单点计算、小数组、需要分支逻辑的地方，numpy 反而更慢而且更难读。
判断标准很简单：

- 数据量 > 1 万且运算可向量化 → numpy
- 需要 if/else 分支、状态机、逐帧闭环 → 纯 Python

本项目的 `recommend.py` 全篇没有 numpy，因为它是逐帧的状态推演；
`stats.py` 全篇是 numpy，因为它是对整张图的聚合。**同一个人写的两个文件，风格完全不同，这是正常的。**

---

## 10. 本项目 Kotlin ↔ Python 对照

| Kotlin | Python | 关系 |
|---|---|---|
| `analysis/FrameAnalyzer.kt` | `shottuner/stats.py` | 同一套统计量，端侧手写循环，PC 端向量化 |
| `analysis/SceneClassifier.kt` | `shottuner/scene.py` | 打分表逐条对齐，阈值必须同步修改 |
| `recommend/ParamRecommender.kt` | `shottuner/recommend.py` | 逐行等价，包括 clamp 顺序 |
| — | `shottuner/simulate.py` | Python 独有：闭环仿真，端侧没有对应物 |
| — | `shottuner/cli.py` | Python 独有：批量分析照片 |

改算法时的正确顺序是：**先改 Python → 跑 pytest → 跑 simulate 看收敛 → 再同步到 Kotlin**。
反过来的话，每次验证都要编译安装一个 APK，十分钟起步。
