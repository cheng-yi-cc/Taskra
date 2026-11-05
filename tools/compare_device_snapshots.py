"""比较覆盖更新前后的已校验快照；任何旧记录或原有附件差异都停止。"""

import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    args = parser.parse_args()
    before = json.loads((args.before / "summary.json").read_text(encoding="utf-8"))
    after = json.loads((args.after / "summary.json").read_text(encoding="utf-8"))
    if before["package"] != after["package"] or before["db_version"] not in (5, 6) or after["db_version"] != 6:
        raise RuntimeError("应用包名或数据库版本不符合已验证的升级路径")
    for name, spec in before["tables"].items():
        # Room 迁移必然更新 schema 身份；全部业务表及已有同步表均须保持一致。
        if name != "room_master_table" and after["tables"].get(name) != spec:
            raise RuntimeError(f"旧数据库记录发生变化：{name}")
    for name, digest in before["protected_files"].items():
        if after["protected_files"].get(name) != digest:
            raise RuntimeError("原有设置或附件发生变化；保留快照并停止")
    print("核验通过：原有数据库记录、设置和附件内容保持一致。")


if __name__ == "__main__":
    main()
