import csv
import os
import re

def parse_garden_map(input_path, output_path):
    # Mapping for common terms to class labels
    TARGET_KEYWORDS = ['dime', 'nickel', 'penny', 'quarter', 'silver ring', 'gold', 'ring']
    JUNK_KEYWORDS = ['pull tab', 'pulltab', 'beavertail', 'nail', 'aluminum trash', 'foil', 'bottle cap', 'junk']

    def get_class(name):
        name_lower = name.lower()
        if any(k in name_lower for k in TARGET_KEYWORDS):
            return "TARGET"
        if any(k in name_lower for k in JUNK_KEYWORDS):
            return "JUNK"
        return "AMBIENT"

    def normalize_to_taxonomy_token(name):
        lowered = name.lower().strip()
        mapping = [
            ("silver dime", "coin:dime:silver-900"),
            ("clad dime", "coin:dime:cupronickel-clad-copper"),
            ("dime", "coin:dime:cupronickel-clad-copper"),
            ("quarter", "coin:quarter:cupronickel-clad-copper"),
            ("zinc penny", "coin:penny:copper-plated-zinc"),
            ("copper penny", "coin:penny:bronze-copper"),
            ("indian head", "coin:penny:bronze-indian-head"),
            ("nickel", "coin:nickel:cupronickel"),
            ("silver ring", "jewelry:ring:silver"),
            ("beaver", "trash:beaver-tail:aluminum"),
            ("pull tab", "trash:pull-tab:aluminum"),
            ("foil", "trash:foil:aluminum"),
            ("bottle cap", "trash:bottle-cap:steel"),
            ("nail", "hardware:nail:steel"),
            ("aluminum trash", "trash:fragment:aluminum"),
            ("ambient", "ambient:background:unknown"),
        ]
        for keyword, token in mapping:
            if keyword in lowered:
                return token
        return "artifact:unknown:unknown"

    data = []
    with open(input_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        for row in reader:
            if not row or not row[0]:
                continue
            
            sample_id = row[0]
            description = row[1] if len(row) > 1 else ""
            
            # Extract depth (e.g., "at 5""", "at 11""", "at 6-7""")
            depth_match = re.search(r'at ([\d\-]+)', description)
            depth = depth_match.group(1) if depth_match else ""
            
            # Remove the depth part from names
            clean_desc = re.sub(r' at [\d\-]+.*', '', description).strip()
            
            # Identify multiple items
            # Common patterns: "," or "and" or "superimposed on" or "with"
            parts = re.split(r',|;| and | superimposed on | with ', clean_desc)
            # Filter out "separation" text and empty parts
            parts = [p.strip() for p in parts if p.strip() and not re.search(r'\d+" separation', p.strip(), re.I)]
            normalized_parts = [normalize_to_taxonomy_token(p) for p in parts]
            
            # Determine if it's mixed
            is_mixed = len(parts) > 1 or "separation" in description.lower() or "superimposed" in description.lower()
            
            # Determine class label
            has_target = any(get_class(p) == "TARGET" for p in parts)
            has_junk = any(get_class(p) == "JUNK" for p in parts)
            
            if has_target:
                main_class = "TARGET"
            elif has_junk:
                main_class = "JUNK"
            else:
                main_class = "AMBIENT"

            data.append({
                'sample_id': sample_id,
                'target_name': "|".join(normalized_parts),
                'class_label': main_class,
                'depth_inches': depth,
                'mixed_flag': "true" if is_mixed else "false",
                'include_in_training': "true",
                'original_description': description
            })

    # Header for new CSV
    fieldnames = ['sample_id', 'target_name', 'class_label', 'depth_inches', 'mixed_flag', 'include_in_training', 'original_description']
    
    with open(output_path, 'w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for entry in data:
            # Overriding default logic to include everything per user request
            entry['include_in_training'] = "true"
            writer.writerow(entry)

if __name__ == "__main__":
    input_csv = "/Users/colincatlin/Documents-NoCloud/audio_app/On Device Audio App/assets/detector_garden_map.csv"
    output_csv = "/Users/colincatlin/Documents-NoCloud/audio_app/On Device Audio App/assets/cleaned_labels.csv"
    parse_garden_map(input_csv, output_csv)
    print(f"Created {output_csv}")
