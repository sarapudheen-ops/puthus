#!/usr/bin/env python3
"""Generate the names/years-only starter catalog. No diagnostic addresses or commands belong here.

Ranges are starter model/generation labels, not a market-specific fitment database. Legacy VW model
ranges are retained when those definitions migrate. Extend this reviewed list rather than inferring
vehicles/variants from VINs or diagnostic labels.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata

# make: model: generation, first year, last year (None = open range)
VEHICLES = {
    "Toyota": {
        "Innova": [("AN40", 2004, 2015), ("AN140 (Crysta)", 2015, None), ("AG10 (Hycross / Zenix)", 2022, None)],
        "Corolla": [("E140 / E150", 2006, 2013), ("E170 / E180", 2013, 2019), ("E210", 2018, None)],
        "Camry": [("XV40", 2006, 2011), ("XV50", 2011, 2017), ("XV70", 2017, 2024), ("XV80", 2024, None)],
        "Hilux": [("AN10 / AN20 / AN30", 2004, 2015), ("AN120 / AN130", 2015, None)],
        "Fortuner": [("AN50 / AN60", 2004, 2015), ("AN150 / AN160", 2015, None)],
    },
    "Lexus": {"IS": [("XE20", 2005, 2013), ("XE30", 2013, None)], "RX": [("AL10", 2008, 2015), ("AL20", 2015, 2022), ("ALA10 / ALH10", 2022, None)]},
    "Hyundai": {"i10": [("PA", 2007, 2013), ("IA", 2013, 2019), ("AC3", 2019, None)], "Elantra": [("AD", 2015, 2020), ("CN7", 2020, None)], "Tucson": [("TL", 2015, 2020), ("NX4", 2020, None)]},
    "Kia": {"Sportage": [("SL", 2010, 2015), ("QL", 2015, 2021), ("NQ5", 2021, None)], "Seltos": [("SP2", 2019, None)], "Sorento": [("UM", 2014, 2020), ("MQ4", 2020, None)]},
    "Suzuki": {"Swift": [("ZC72 / ZD72", 2010, 2017), ("AZ", 2017, 2023), ("ZC / ZD 2023", 2023, None)], "Ertiga": [("ZE", 2012, 2018), ("NC", 2018, None)], "Dzire": [("Third generation", 2017, 2024), ("Fourth generation", 2024, None)]},
    "Tata": {"Nexon": [("First generation", 2017, None)], "Harrier": [("First generation", 2019, None)], "Safari": [("Body-on-frame", 1998, 2019), ("Monocoque", 2021, None)]},
    "Mahindra": {"Scorpio": [("First generation", 2002, 2022), ("Scorpio-N", 2022, None)], "Thar": [("First generation", 2010, 2020), ("Second generation", 2020, None)], "XUV700": [("First generation", 2021, None)]},
    "Honda": {"Civic": [("FB / FG", 2011, 2015), ("FC / FK", 2015, 2021), ("FE / FL", 2021, None)], "City": [("GM2 / GM3", 2008, 2014), ("GM4 / GM5 / GM6 / GM9", 2014, 2020), ("GN", 2019, None)], "CR-V": [("RM", 2011, 2016), ("RW / RT", 2016, 2022), ("RS", 2022, None)]},
    "Nissan": {"Patrol": [("Y61", 1997, 2024), ("Y62", 2010, 2024), ("Y63", 2024, None)], "X-Trail": [("T31", 2007, 2014), ("T32", 2013, 2022), ("T33", 2021, None)], "Almera": [("N17", 2011, 2019), ("N18", 2019, None)]},
    "Ford": {"Focus": [("Mk3", 2010, 2018), ("Mk4", 2018, 2025)], "Fiesta": [("Mk7", 2008, 2017), ("Mk8", 2017, 2023)], "Ranger": [("T6 / P375", 2011, 2022), ("P703", 2022, None)]},
    "Chevrolet": {"Cruze": [("J300", 2008, 2016), ("J400", 2016, 2023)], "Spark": [("M300", 2009, 2015), ("M400", 2015, 2022)]},
    "Volkswagen": {"Tiguan": [("5N", 2007, 2016), ("AD1", 2016, 2024)], "Golf": [("5G / Mk7", 2013, 2020), ("CD / Mk8", 2019, None)], "Passat": [("B7", 2010, 2015), ("B8", 2015, 2023)]},
    "Audi": {"A3": [("8P", 2003, 2013), ("8V", 2013, 2020), ("8Y", 2020, None)], "A4": [("B8 / 8K", 2007, 2015), ("B9 / 8W", 2015, 2024)]},
    "Škoda": {"Octavia": [("5E / Mk3", 2013, 2020), ("NX / Mk4", 2019, None)], "Superb": [("3T / Mk2", 2008, 2015), ("3V / Mk3", 2015, 2023)]},
    "SEAT": {"Leon": [("5F / Mk3", 2013, 2020), ("KL / Mk4", 2020, None)], "Ibiza": [("6J", 2008, 2017), ("KJ", 2017, None)]},
    "Cupra": {"Formentor": [("KM", 2020, None)], "Leon": [("KL", 2020, None)]},
    "BMW": {"3 Series": [("E90 / E91 / E92 / E93", 2005, 2013), ("F30 / F31 / F34", 2011, 2019), ("G20 / G21", 2018, None)], "5 Series": [("F10 / F11", 2010, 2017), ("G30 / G31", 2016, 2024), ("G60 / G61", 2023, None)]},
    "Mercedes-Benz": {"C-Class": [("W204", 2007, 2014), ("W205", 2014, 2021), ("W206", 2021, None)], "E-Class": [("W212", 2009, 2016), ("W213", 2016, 2023), ("W214", 2023, None)]},
    "Renault": {"Clio": [("III", 2005, 2014), ("IV", 2012, 2019), ("V", 2019, None)], "Megane": [("II", 2002, 2009), ("III", 2008, 2016)]},
    "Peugeot": {"208": [("A9", 2012, 2019), ("P21", 2019, None)], "308": [("T7", 2007, 2013), ("T9", 2013, 2021), ("P5", 2021, None)]},
    "Citroën": {"C3": [("II (Europe)", 2009, 2016), ("III (Europe)", 2016, 2024), ("IV (Europe)", 2024, None)], "C4": [("I", 2004, 2010), ("II", 2010, 2018), ("III", 2020, None)]},
    "Mazda": {"3": [("BL", 2008, 2013), ("BM / BN", 2013, 2019), ("BP", 2019, None)], "6": [("GH", 2007, 2012), ("GJ / GL", 2012, 2024)]},
    "Mitsubishi": {"Pajero": [("V60 / V70", 1999, 2006), ("V80 / V90", 2006, 2021)], "Outlander": [("CW", 2005, 2012), ("GF / GG", 2012, 2021), ("GN", 2021, None)]},
    "Subaru": {"Forester": [("SJ", 2012, 2018), ("SK", 2018, 2024), ("SL", 2024, None)], "Impreza": [("GJ / GP", 2011, 2016), ("GK / GT", 2016, 2023), ("GU", 2023, None)]},
    "Volvo": {"XC60": [("I", 2008, 2017), ("II", 2017, None)], "XC90": [("I", 2002, 2014), ("II", 2014, None)]},
    "Land Rover": {"Discovery": [("4 / L319", 2009, 2016), ("5 / L462", 2017, None)], "Range Rover Sport": [("L320", 2005, 2013), ("L494", 2013, 2022), ("L461", 2022, None)]},
    "Jeep": {"Grand Cherokee": [("WK2", 2010, 2022), ("WL", 2021, None)], "Wrangler": [("JK", 2006, 2018), ("JL", 2017, None)]},
}


def identity(level, parent, name):
    normalized = unicodedata.normalize('NFD', name.strip())
    normalized = ''.join(c for c in normalized if not unicodedata.combining(c)).lower()
    normalized = re.sub(r'\s+', ' ', normalized)
    return level + '-' + hashlib.sha256(((parent or '') + '|' + normalized).encode()).digest()[:10].hex()


def generate():
    nodes = []
    for make, models in VEHICLES.items():
        mi = identity('make', None, make)
        nodes.append(dict(id=mi, level='make', name=make))
        for model, gens in models.items():
            model_id = identity('model', mi, model)
            nodes.append(dict(id=model_id, parentId=mi, level='model', name=model))
            for name, first, last in gens:
                n = dict(id=identity('generation', model_id, name), parentId=model_id, level='generation', name=name, yearFrom=first)
                if last is not None:
                    n['yearTo'] = last
                nodes.append(n)
    return dict(format='bod3-vehicle-catalog', formatVersion=1, id='starter-catalog', name='Starter vehicle names and generations', version=1,
                description='Names and broad generation/model-year ranges only; markets and model years vary. Open end dates are not a coverage promise. No ECU, PID, coding or test definitions. Manual selection is always required.', nodes=nodes)


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[1] / 'app/src/main/assets/catalog/starter.json'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if the checked-in catalog differs; do not write it')
    args = parser.parse_args()
    data = generate()
    text = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if not output.exists() or output.read_text() != text:
            parser.exit(1, 'Starter catalog is stale; run python3 tools/generate_catalog_seed.py\n')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text)
    print(f"{len(VEHICLES)} makes; {len(data['nodes'])} total catalog nodes; no diagnostic definitions")
