import sys
from pathlib import Path

# 让测试可以直接 import shottuner，无需安装为包
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
