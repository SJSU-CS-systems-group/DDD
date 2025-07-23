import argparse
import asyncio
import os
import re
import sys
import xml.etree.ElementTree as ET

from googletrans import Translator


async def translate(file, language):
    if file.endswith('.xml'):
        print(f"Translating {file} to {language}...")
        translator = Translator()
        dest_dir = (f"{os.path.dirname(file)}-{language}")
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, os.path.basename(file))
        existing_translations = {}
        if os.path.exists(dest):
            old_tree = ET.parse(dest)
            for string in old_tree.getroot().findall('string'):
                existing_translations[string.get('name')] = string.text

        tree = ET.parse(file)
        root = tree.getroot()

        for string in root.findall('string'):
            original = string.text
            if string.get('name') in existing_translations:
                string.text = existing_translations[string.get('name')]
            else:
                fixed_original = original.replace("\\'", "'").replace("\\n", "%n")
                translated = await translator.translate(fixed_original, src='en', dest=language)
                # Note: we don't need to change back %n because %n is valid substitution for \n
                string.text = translated.text.replace("'", "\\'")
                # Fix for %1$s style placeholders
                string.text = re.sub(r'%\s*(\d+)\s*\$\s*([sd])', r'%\1$\2', string.text)

        tree.write(dest, encoding='utf-8')
    else:
        print(f"Unsupported file type: {file}. Only XML files are supported for translation.", file=sys.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Translate XML files or text files.')
    parser.add_argument('file', type=str, help='The file (or directory with --recursive) to translate.')
    parser.add_argument('languages', type=str, help='The comma separated target language codes (e.g., "fr" for French).')
    parser.add_argument('--recursive', action='store_true',
                        help='Recursively translate files named strings.xml or messages.properties.')
    args = parser.parse_args()

    recursive_files = []
    if args.recursive:
        for root, dirs, files in os.walk(args.file):
            for file in files:
                if (root.endswith("values") and file.endswith('strings.xml')) or file.endswith('messages.properties'):
                    recursive_files += [os.path.join(root, file)]

    for language in args.languages.split(','):
        if args.recursive:
            for file in recursive_files:
                asyncio.run(translate(file, language))
        else:
            asyncio.run(translate(args.file, language))
