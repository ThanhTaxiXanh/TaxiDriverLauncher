#!/usr/bin/env python3
"""Pure parsing checks for safe OSM numeric sign extraction."""
import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).with_name("build-vietnam-osm.py")
spec = importlib.util.spec_from_file_location("vietnam_osm", path)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class SpeedSigns(unittest.TestCase):
    def test_numeric_limit_must_be_explicit_on_sign(self):
        tags = {"traffic_sign": "VN:P.127", "maxspeed": "60"}
        self.assertEqual(60, mod.explicit_sign_speed(tags))
        self.assertEqual("SPEED_LIMIT", mod.classified_sign(tags))

    def test_country_code_alone_cannot_imply_limit(self):
        self.assertIsNone(mod.explicit_sign_speed({"traffic_sign": "VN:P.127"}))
        self.assertEqual("OTHER", mod.classified_sign({"traffic_sign": "VN:P.127"}))

    def test_road_class_does_not_imply_sign_limit(self):
        self.assertIsNone(mod.explicit_sign_speed({"maxspeed": "60", "highway": "primary"}))

    def test_unknown_value_cannot_be_displayed_as_numeric_limit(self):
        self.assertIsNone(mod.explicit_sign_speed({"traffic_sign": "maxspeed", "maxspeed": "signals"}))
        self.assertIsNone(mod.explicit_sign_speed({"traffic_sign": "maxspeed", "maxspeed": "default"}))
        self.assertIsNone(mod.explicit_sign_speed({"traffic_sign": "maxspeed", "maxspeed": "20;40"}))

    def test_mph_converted_safely(self):
        self.assertEqual(80, mod.explicit_sign_speed({
            "traffic_sign": "speed_limit", "traffic_sign:maxspeed": "50 mph",
        }))

    def test_existing_stop_sign_preserved(self):
        self.assertEqual("STOP", mod.classified_sign({"highway": "stop"}))


if __name__ == "__main__":
    unittest.main()
