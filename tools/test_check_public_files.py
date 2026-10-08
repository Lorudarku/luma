"""Regresiones del control público con datos completamente ficticios y repositorios temporales."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from check_public_files import candidates, findings


class PublicFilesTest(unittest.TestCase):
    def test_private_files_are_excluded_without_initializing_user_repository(self):
        """Comprueba exclusiones en un árbol sin Git; salida pública no contiene preferencias ni secretos."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.git').mkdir()
            (root / '.gitignore').write_text('.idea/\n.env\n*.db\n')
            (root / '.idea').mkdir()
            (root / '.idea' / 'device.xml').write_text('private fixture')
            (root / '.env').write_text('private fixture')
            (root / 'finance.db').write_bytes(b'fictional database')
            (root / 'README.md').write_text('public fixture')
            self.assertEqual(['.gitignore', 'README.md'], candidates(root))
            self.assertEqual([], list((root / '.git').iterdir()))

    def test_forced_tracking_does_not_bypass_privacy_check(self):
        """Un archivo privado rastreado se rechaza aunque .gitignore lo excluya; no se crea ningún commit."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '--quiet', str(root)], check=True, capture_output=True)
            (root / '.gitignore').write_text('.env\n.env.*\n')
            for name in ['.env', '.env.production']:
                (root / name).write_text('fictional private fixture')
            subprocess.run(['git', '-C', str(root), 'add', '-f', '.env', '.env.production'], check=True, capture_output=True)
            result = findings(root, candidates(root))
            self.assertTrue(any('.env:' in item for item in result))
            self.assertTrue(any('.env.production:' in item for item in result))

    def test_secret_values_never_appear_in_findings(self):
        """Inyecta token/ruta ficticios; la salida solo contiene ubicación y tipo, nunca el valor."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            token = 'ghp_' + 'A' * 40
            private_path = '/home' + '/fictional-owner/work'
            (root / 'fixture.txt').write_text(token + '\n' + private_path)
            result = findings(root, ['fixture.txt'])
            self.assertEqual(2, len(result))
            self.assertFalse(any(token in item or private_path in item for item in result))


if __name__ == '__main__':
    unittest.main()
