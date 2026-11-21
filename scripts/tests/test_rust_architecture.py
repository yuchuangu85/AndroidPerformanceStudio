"""Behavior checks for the offline rust-desktop architecture gates."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_rust_architecture as gate


class RulesTest(unittest.TestCase):
    def test_dependency_kind_and_real_package_identity(self):
        roots = {n: Path('/ws') / n for n in
                 ('aps-contracts', 'aps-hprof-engine', 'aps-ui-foundation', 'aps-json')}
        def errors(owner, dest, kind=None, **extra):
            dep = dict(name=dest, kind=kind, path=str(roots[dest]), **extra)
            return gate.edge_errors(owner, [dep], roots)
        self.assertTrue(errors('aps-contracts', 'aps-hprof-engine', rename='codec',
                               optional=True, target='cfg(windows)'))
        self.assertFalse(errors('aps-contracts', 'aps-hprof-engine', 'dev'))
        self.assertFalse(errors('aps-ui-foundation', 'aps-json', 'build'))
        self.assertTrue(errors('aps-ui-foundation', 'aps-json'))

    def test_private_source_and_local_controls(self):
        with tempfile.TemporaryDirectory() as tmp:
            crate = Path(tmp) / 'engine'
            (crate / 'src').mkdir(parents=True)
            file = crate / 'src/lib.rs'
            for item in [
                '#[path = "../../neighbor/src/wire.rs"] mod wire;',
                '#[cfg_attr(unix, path=r#"../../neighbor/src/wire.rs"#)] mod wire;',
                'include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../neighbor/src/wire.rs"));',
                'include_str!( /* note */ "../../neighbor/src/wire.rs");',
                'include_bytes! { "../../neighbor/src/wire.rs" };',
                'include!(concat!(env!("OUT_DIR"), "/../secret.rs"));',
                r'include!(concat!(env!("OUT_DIR"), "/sub\\..\\secret.rs"));',
                'include!(unknown_path!());',
            ]:
                with self.subTest(item=item):
                    self.assertTrue(gate.source_errors(file, crate, item))
            controls = r'''
                // #[path = "../../neighbor/src/wire.rs"]
                /* nested /* comment */ include!("../../neighbor/src/wire.rs"); */
                const EXAMPLE: &str = r##"include!("../../neighbor/src/wire.rs")"##;
                include_str!("../README.md");
                include_bytes!("../../assets/icon.png");
                include!(concat!(env!("OUT_DIR"), "/strings.rs"));
                #[path = "../tests/helper.rs"] mod helper;
            '''
            self.assertEqual(gate.source_errors(file, crate, controls), [])

    def test_real_cargo_metadata_and_cross_crate_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp).resolve()
            (workspace / 'Cargo.toml').write_text(
                '[workspace]\nmembers=["crates/*"]\nresolver="2"\n'
                '[workspace.dependencies]\ncodec={package="aps-hprof-engine", '
                'path="crates/aps-hprof-engine"}\n')
            for name in ['aps-contracts', 'aps-hprof-engine']:
                root = workspace / 'crates' / name
                (root / 'src').mkdir(parents=True)
                (root / 'src/lib.rs').touch()
                (root / 'Cargo.toml').write_text(
                    f'[package]\nname="{name}"\nversion="0.1.0"\nedition="2021"\n')
            manifest = workspace / 'crates/aps-contracts/Cargo.toml'
            with manifest.open('a') as f:
                f.write("[target.'cfg(windows)'.dependencies]\n"
                        'codec={workspace=true, optional=true}\n'
                        '[[bin]]\nname="other"\npath="../aps-hprof-engine/src/lib.rs"\n')
            subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=workspace, check=True)
            errors = gate.check(workspace)
            self.assertEqual(len(errors), 2, errors)
            self.assertTrue(any('aps-contracts --normal--> aps-hprof-engine' in e for e in errors))
            self.assertTrue(any('Cargo target imports source outside crate' in e for e in errors))


if __name__ == '__main__':
    unittest.main()
